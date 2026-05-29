package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

// tcpControl wraps the agent's TCP control socket. Writes are serialized
// (the agent reads each JSON line as one record, so two concurrent writes
// could interleave into corrupt JSON). The reader side runs on its own
// goroutine, logs anything the agent sends back (mostly OPEN_FAIL), and
// signals close via .done.
type tcpControl struct {
	conn    net.Conn
	w       *bufio.Writer
	writeMu sync.Mutex
	closed  atomic.Bool
	done    chan struct{}
}

func (c *tcpControl) sendLine(payload string) error {
	if c.closed.Load() {
		return errors.New("tcp control closed")
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	if _, err := c.w.WriteString(payload); err != nil {
		return err
	}
	if err := c.w.WriteByte('\n'); err != nil {
		return err
	}
	return c.w.Flush()
}

func (c *tcpControl) close() {
	if c.closed.Swap(true) {
		return
	}
	_ = c.conn.Close()
	close(c.done)
}

// runTCP accepts uplink connections forever. Each fresh socket starts with
// the 6-byte TUNL header, after which we split off control vs data paths.
func runTCP(ctx context.Context, hub *Hub, port int) {
	ln, err := listenTCP(ctx, addrStr(hub.bindAddr, port))
	if err != nil {
		log.Printf("tcp listen: %v", err)
		return
	}
	defer ln.Close()
	hub.tcpReady.Store(true)
	log.Printf("tcp: listening on %s", ln.Addr())

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		c, err := ln.Accept()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("tcp accept: %v", err)
			continue
		}
		go handleTCPConn(ctx, hub, c)
	}
}

func handleTCPConn(ctx context.Context, hub *Hub, c net.Conn) {
	// 6-byte header window: 'T','U','N','L', version, connType.
	_ = c.SetReadDeadline(time.Now().Add(15 * time.Second))
	hdr := make([]byte, 6)
	if _, err := io.ReadFull(c, hdr); err != nil {
		log.Printf("tcp: header read from %s: %v", c.RemoteAddr(), err)
		c.Close()
		return
	}
	_ = c.SetReadDeadline(time.Time{})

	if hdr[0] != wireMagic[0] || hdr[1] != wireMagic[1] || hdr[2] != wireMagic[2] || hdr[3] != wireMagic[3] {
		log.Printf("tcp: bad magic from %s: %q", c.RemoteAddr(), hdr[:4])
		c.Close()
		return
	}
	if hdr[4] != wireVersion {
		log.Printf("tcp: bad version from %s: %d", c.RemoteAddr(), hdr[4])
		c.Close()
		return
	}

	switch hdr[5] {
	case connTypeControl:
		handleTCPControl(ctx, hub, c)
	case connTypeData:
		handleTCPData(hub, c)
	default:
		log.Printf("tcp: unknown connType=%#x from %s", hdr[5], c.RemoteAddr())
		c.Close()
	}
}

// handleTCPControl reads the AUTH command, validates the key, responds with
// AUTH_OK, registers the conn as the hub's active control, and then enters
// a read loop that just logs anything the agent emits back (mostly
// OPEN_FAIL). When the read loop ends, the control is cleared so the API
// reports "no client" cleanly.
func handleTCPControl(ctx context.Context, hub *Hub, c net.Conn) {
	r := bufio.NewReader(c)

	_ = c.SetReadDeadline(time.Now().Add(30 * time.Second))
	line, err := r.ReadString('\n')
	_ = c.SetReadDeadline(time.Time{})
	if err != nil {
		log.Printf("tcp ctrl: read AUTH: %v", err)
		c.Close()
		return
	}
	var auth struct {
		Command string `json:"command"`
		Key     string `json:"key"`
		UUID    string `json:"uuid"`
	}
	if err := json.Unmarshal([]byte(line), &auth); err != nil {
		log.Printf("tcp ctrl: bad AUTH json: %v", err)
		c.Close()
		return
	}
	if auth.Command != "AUTH" {
		log.Printf("tcp ctrl: expected AUTH, got %q", auth.Command)
		c.Close()
		return
	}
	if hub.authKey != "" && auth.Key != hub.authKey {
		log.Printf("tcp ctrl: AUTH key mismatch (got %q)", auth.Key)
		// Closing without AUTH_OK is what the agent expects for "denied".
		c.Close()
		return
	}

	w := bufio.NewWriter(c)
	ctrl := &tcpControl{conn: c, w: w, done: make(chan struct{})}
	if err := ctrl.sendLine(`{"command":"AUTH_OK"}`); err != nil {
		log.Printf("tcp ctrl: AUTH_OK write: %v", err)
		c.Close()
		return
	}
	log.Printf("tcp ctrl: AUTH_OK to %s uuid=%s", c.RemoteAddr(), auth.UUID)

	hub.mu.Lock()
	if hub.tcpCtrl != nil {
		old := hub.tcpCtrl
		hub.tcpCtrl = nil
		hub.mu.Unlock()
		old.close()
		hub.mu.Lock()
	}
	hub.tcpCtrl = ctrl
	hub.mu.Unlock()

	// Reader loop: log whatever the agent sends back, exit on EOF.
	go func() {
		<-ctx.Done()
		ctrl.close()
	}()
	for {
		l, err := r.ReadString('\n')
		if err != nil {
			break
		}
		log.Printf("tcp ctrl recv: %s", trimNL(l))
	}

	hub.mu.Lock()
	if hub.tcpCtrl == ctrl {
		hub.tcpCtrl = nil
	}
	hub.mu.Unlock()
	ctrl.close()
}

// handleTCPData reads a 32-char ASCII hex token and delivers the socket to
// whichever API handler registered that token via Hub.registerPending. The
// API handler then owns the socket — it bridges bytes to the test stream
// and closes when done.
func handleTCPData(hub *Hub, c net.Conn) {
	_ = c.SetReadDeadline(time.Now().Add(60 * time.Second))
	tok := make([]byte, tokenHexLen)
	if _, err := io.ReadFull(c, tok); err != nil {
		log.Printf("tcp data: token read: %v", err)
		c.Close()
		return
	}
	_ = c.SetReadDeadline(time.Time{})

	token := string(tok)
	ch := hub.takePending(token)
	if ch == nil {
		log.Printf("tcp data: no pending tunnel for token %s", shortToken(token))
		c.Close()
		return
	}
	select {
	case ch <- c:
	default:
		// Pending handler already gave up — drop the socket.
		c.Close()
	}
}

func trimNL(s string) string {
	for len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r') {
		s = s[:len(s)-1]
	}
	return s
}

func shortToken(t string) string {
	if len(t) < 6 {
		return t
	}
	return t[:6]
}

// sendOpenCommand asks the connected agent to dial host:port and bridge
// the resulting target socket to the data socket that comes in with this
// token. Returns the channel where that data socket will be delivered.
// Caller must call dropPending(token) on early failure paths.
func (hub *Hub) sendOpenCommand(token, host string, port int) (chan net.Conn, error) {
	hub.mu.Lock()
	ctrl := hub.tcpCtrl
	hub.mu.Unlock()
	if ctrl == nil {
		return nil, errors.New("no TCP client connected")
	}
	ch := hub.registerPending(token)
	payload := fmt.Sprintf(`{"command":"OPEN","token":%q,"host":%q,"port":%d}`, token, host, port)
	if err := ctrl.sendLine(payload); err != nil {
		hub.dropPending(token)
		return nil, fmt.Errorf("send OPEN: %w", err)
	}
	return ch, nil
}

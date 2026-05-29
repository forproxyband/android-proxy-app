package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"time"

	"github.com/quic-go/quic-go"
)

// runQUIC accepts QUIC uplinks. Each connection's first stream is the
// control stream (client-initiated bidi) — the agent sends AUTH on it
// immediately. We register the active *quic.Conn on the hub so the API
// can open further server-initiated streams for tunnels.
func runQUIC(ctx context.Context, hub *Hub, port int, tlsCfg *tls.Config) {
	udpAddr, err := net.ResolveUDPAddr("udp", addrStr(hub.bindAddr, port))
	if err != nil {
		log.Printf("quic resolve: %v", err)
		return
	}
	pc, err := net.ListenUDP("udp", udpAddr)
	if err != nil {
		log.Printf("quic udp listen: %v", err)
		return
	}
	defer pc.Close()

	qcfg := &quic.Config{
		MaxIdleTimeout:        2 * time.Minute,
		MaxIncomingStreams:    1 << 16,
		MaxIncomingUniStreams: 1 << 16,
		KeepAlivePeriod:       20 * time.Second,
		Allow0RTT:             false,
	}
	ln, err := quic.Listen(pc, tlsCfg, qcfg)
	if err != nil {
		log.Printf("quic listen: %v", err)
		return
	}
	defer ln.Close()
	hub.quicReady.Store(true)
	log.Printf("quic: listening on %s ALPN=%s", pc.LocalAddr(), quicALPN)

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	for {
		conn, err := ln.Accept(ctx)
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			log.Printf("quic accept: %v", err)
			continue
		}
		go handleQUICConn(ctx, hub, conn)
	}
}

func handleQUICConn(ctx context.Context, hub *Hub, conn quic.Connection) {
	remote := conn.RemoteAddr()
	log.Printf("quic: new conn from %s", remote)

	// First client-initiated stream = control. Bound on stream-accept time
	// so a connection that finishes the QUIC handshake but never opens a
	// stream doesn't pin us forever.
	streamCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	ctrlStream, err := conn.AcceptStream(streamCtx)
	cancel()
	if err != nil {
		log.Printf("quic: accept control stream from %s: %v", remote, err)
		_ = conn.CloseWithError(0, "no control stream")
		return
	}

	if err := handleQUICAuth(hub, ctrlStream); err != nil {
		log.Printf("quic: AUTH failed for %s: %v", remote, err)
		_ = ctrlStream.Close()
		_ = conn.CloseWithError(0, "auth failed")
		return
	}
	log.Printf("quic: AUTH_OK to %s", remote)

	hub.mu.Lock()
	if hub.quicConn != nil {
		old := hub.quicConn
		hub.quicConn = nil
		hub.mu.Unlock()
		_ = old.CloseWithError(0, "superseded")
		hub.mu.Lock()
	}
	hub.quicConn = conn
	hub.mu.Unlock()

	// Tie connection lifetime to the control stream + global shutdown.
	// Read forever from the control stream — anything the agent sends back
	// goes to the log; on EOF we tear down the conn record.
	go func() {
		<-ctx.Done()
		_ = conn.CloseWithError(0, "shutdown")
	}()
	r := bufio.NewReader(ctrlStream)
	for {
		line, err := r.ReadString('\n')
		if line != "" {
			log.Printf("quic ctrl recv: %s", trimNL(line))
		}
		if err != nil {
			break
		}
	}

	hub.mu.Lock()
	if hub.quicConn == conn {
		hub.quicConn = nil
	}
	hub.mu.Unlock()
	_ = conn.CloseWithError(0, "control stream ended")
}

func handleQUICAuth(hub *Hub, s quic.Stream) error {
	r := bufio.NewReader(s)
	if err := s.SetReadDeadline(time.Now().Add(30 * time.Second)); err != nil {
		return fmt.Errorf("set deadline: %w", err)
	}
	line, err := r.ReadString('\n')
	if err != nil {
		return fmt.Errorf("read AUTH: %w", err)
	}
	_ = s.SetReadDeadline(time.Time{})

	var auth struct {
		Command string `json:"command"`
		Key     string `json:"key"`
		UUID    string `json:"uuid"`
	}
	if err := json.Unmarshal([]byte(line), &auth); err != nil {
		return fmt.Errorf("bad AUTH json: %w", err)
	}
	if auth.Command != "AUTH" {
		return fmt.Errorf("expected AUTH, got %q", auth.Command)
	}
	if hub.authKey != "" && auth.Key != hub.authKey {
		return fmt.Errorf("key mismatch %q", auth.Key)
	}
	if _, err := s.Write([]byte(`{"command":"AUTH_OK"}` + "\n")); err != nil {
		return fmt.Errorf("write AUTH_OK: %w", err)
	}
	return nil
}

// openQUICTunnel asks the connected QUIC agent to dial host:port: opens a
// server-initiated bidi stream and writes the JSON header. Caller receives
// the stream and bridges the test payload through it. The stream's input
// side carries bytes the agent reads from its TCP target socket; the
// output side feeds bytes that the agent then writes to the target.
func (hub *Hub) openQUICTunnel(ctx context.Context, host string, port int) (quic.Stream, error) {
	hub.mu.Lock()
	conn := hub.quicConn
	hub.mu.Unlock()
	if conn == nil {
		return nil, errors.New("no QUIC client connected")
	}
	openCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	stream, err := conn.OpenStreamSync(openCtx)
	if err != nil {
		return nil, fmt.Errorf("open stream: %w", err)
	}
	header := fmt.Sprintf(`{"host":%q,"port":%d}`+"\n", host, port)
	if _, err := stream.Write([]byte(header)); err != nil {
		_ = stream.Close()
		return nil, fmt.Errorf("write header: %w", err)
	}
	return stream, nil
}

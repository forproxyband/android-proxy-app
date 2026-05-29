package main

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// runAPI exposes a tiny HTTP API the JUnit tests on the Android side hit
// to (a) verify the server is up, (b) drive tunnel roundtrip experiments.
// All endpoints are blocking — the JUnit test waits for the JSON reply.
func runAPI(ctx context.Context, hub *Hub, port int) {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		if !hub.tcpReady.Load() || !hub.quicReady.Load() {
			http.Error(w, "not ready", http.StatusServiceUnavailable)
			return
		}
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		hub.mu.Lock()
		tcp := hub.tcpCtrl != nil
		quic := hub.quicConn != nil
		hub.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]any{
			"tcp_client":  tcp,
			"quic_client": quic,
		})
	})
	mux.HandleFunc("/tests/tunnel-roundtrip", func(w http.ResponseWriter, r *http.Request) {
		handleTunnelRoundtrip(r.Context(), hub, w, r)
	})

	srv := &http.Server{
		Addr:         addrStr(hub.bindAddr, port),
		Handler:      mux,
		ReadTimeout:  10 * time.Second,
		WriteTimeout: 120 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(shutCtx)
	}()
	log.Printf("api: listening on %s", srv.Addr)
	if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Printf("api: %v", err)
	}
}

// handleTunnelRoundtrip:
//  1. Generates N random bytes.
//  2. Opens a tunnel from server → agent, target = our own echo listener
//     reachable from the agent at hub-host:echo-port. The agent dials that
//     target and bridges the tunnel bytes to it.
//  3. Pumps the N bytes into the tunnel and reads N bytes back, expecting
//     a byte-perfect copy (echo target loops bytes back, agent bridges
//     them back through the tunnel).
//  4. Returns SHA-256 of sent vs received as proof.
func handleTunnelRoundtrip(ctx context.Context, hub *Hub, w http.ResponseWriter, r *http.Request) {
	bytesParam := r.URL.Query().Get("bytes")
	if bytesParam == "" {
		bytesParam = "65536"
	}
	n, err := strconv.Atoi(bytesParam)
	if err != nil || n <= 0 || n > 16<<20 {
		writeErr(w, http.StatusBadRequest, fmt.Sprintf("bad bytes=%q (must be 1..16MiB)", bytesParam))
		return
	}
	transport := r.URL.Query().Get("transport")
	if transport == "" {
		transport = "quic"
	}

	host := r.URL.Query().Get("target-host")
	if host == "" {
		host = "10.0.2.2" // Android emulator → host loopback
	}

	payload := make([]byte, n)
	if _, err := rand.Read(payload); err != nil {
		writeErr(w, http.StatusInternalServerError, "rand: "+err.Error())
		return
	}
	sentHash := sha256.Sum256(payload)

	pumpCtx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()

	var recv []byte
	switch transport {
	case "tcp":
		recv, err = roundtripTCP(pumpCtx, hub, host, payload)
	case "quic":
		recv, err = roundtripQUIC(pumpCtx, hub, host, payload)
	default:
		writeErr(w, http.StatusBadRequest, "transport must be tcp|quic")
		return
	}
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":        false,
			"error":     err.Error(),
			"bytes":     n,
			"transport": transport,
		})
		return
	}
	recvHash := sha256.Sum256(recv)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         len(recv) == n && recvHash == sentHash,
		"bytes":      n,
		"recv_bytes": len(recv),
		"transport":  transport,
		"hash_sent":  hex.EncodeToString(sentHash[:]),
		"hash_recv":  hex.EncodeToString(recvHash[:]),
	})
}

func roundtripTCP(ctx context.Context, hub *Hub, host string, payload []byte) ([]byte, error) {
	token, err := randomHexToken()
	if err != nil {
		return nil, err
	}
	ch, err := hub.sendOpenCommand(token, host, hub.echoPort)
	if err != nil {
		return nil, err
	}
	var sock net.Conn
	select {
	case sock = <-ch:
	case <-ctx.Done():
		hub.dropPending(token)
		return nil, errors.New("timed out waiting for data socket")
	}
	defer sock.Close()
	return pumpFullDuplex(ctx, sock, sock, payload)
}

func roundtripQUIC(ctx context.Context, hub *Hub, host string, payload []byte) ([]byte, error) {
	stream, err := hub.openQUICTunnel(ctx, host, hub.echoPort)
	if err != nil {
		return nil, err
	}
	defer stream.Close()
	return pumpFullDuplex(ctx, stream, stream, payload)
}

// pumpFullDuplex writes payload into w concurrently with reading len(payload)
// bytes back from r. Returns the received bytes (or what was received
// before failure). Both halves run as goroutines — the writer half closes
// nothing, since the caller owns r/w lifecycle.
func pumpFullDuplex(ctx context.Context, r io.Reader, w io.Writer, payload []byte) ([]byte, error) {
	type writeResult struct{ err error }
	type readResult struct {
		buf []byte
		err error
	}
	wDone := make(chan writeResult, 1)
	rDone := make(chan readResult, 1)

	go func() {
		_, err := io.Copy(w, &chunkedReader{src: payload})
		wDone <- writeResult{err}
	}()
	go func() {
		buf := make([]byte, len(payload))
		_, err := io.ReadFull(r, buf)
		rDone <- readResult{buf, err}
	}()

	var (
		recv []byte
		mu   sync.Mutex
		errs []string
	)
	pending := 2
	for pending > 0 {
		select {
		case wr := <-wDone:
			if wr.err != nil {
				mu.Lock()
				errs = append(errs, "write: "+wr.err.Error())
				mu.Unlock()
			}
			pending--
		case rr := <-rDone:
			if rr.err != nil {
				mu.Lock()
				errs = append(errs, "read: "+rr.err.Error())
				mu.Unlock()
			}
			recv = rr.buf
			pending--
		case <-ctx.Done():
			return recv, ctx.Err()
		}
	}
	if len(errs) > 0 {
		return recv, errors.New(joinErrs(errs))
	}
	return recv, nil
}

// chunkedReader hands out src in ~64 KiB chunks. Pure io.Reader avoids
// io.Copy choosing a giant buffer; the kernel/QUIC layer paces from here.
type chunkedReader struct {
	src []byte
	pos int
}

func (c *chunkedReader) Read(p []byte) (int, error) {
	if c.pos >= len(c.src) {
		return 0, io.EOF
	}
	n := copy(p, c.src[c.pos:])
	c.pos += n
	return n, nil
}

func randomHexToken() (string, error) {
	raw := make([]byte, 16)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return hex.EncodeToString(raw), nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

func joinErrs(es []string) string {
	out := ""
	for i, e := range es {
		if i > 0 {
			out += "; "
		}
		out += e
	}
	return out
}

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
	mux.HandleFunc("/tests/tunnel-roundtrip-concurrent", func(w http.ResponseWriter, r *http.Request) {
		handleTunnelConcurrent(r.Context(), hub, w, r)
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
	// target-port: defaults to our echo target. Tests for error paths
	// (OPEN_FAIL, refused TCP, etc.) override with an unreachable port.
	targetPort := hub.echoPort
	if p := r.URL.Query().Get("target-port"); p != "" {
		v, perr := strconv.Atoi(p)
		if perr != nil || v <= 0 || v > 65535 {
			writeErr(w, http.StatusBadRequest, "bad target-port="+p)
			return
		}
		targetPort = v
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
		recv, err = roundtripTCP(pumpCtx, hub, host, targetPort, payload)
	case "quic":
		recv, err = roundtripQUIC(pumpCtx, hub, host, targetPort, payload)
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

// handleTunnelConcurrent fans out `count` parallel round-trips through
// `count` separate tunnels and reports the aggregate. For QUIC this
// exercises stream multiplexing on a single connection; for TCP it
// exercises the data-socket warm pool + token matchmaking under
// contention.
func handleTunnelConcurrent(ctx context.Context, hub *Hub, w http.ResponseWriter, r *http.Request) {
	count, _ := strconv.Atoi(r.URL.Query().Get("count"))
	if count <= 0 {
		count = 8
	}
	if count > 64 {
		writeErr(w, http.StatusBadRequest, "count must be 1..64")
		return
	}
	bytesParam := r.URL.Query().Get("bytes")
	if bytesParam == "" {
		bytesParam = "32768"
	}
	n, err := strconv.Atoi(bytesParam)
	if err != nil || n <= 0 || n > 1<<20 {
		writeErr(w, http.StatusBadRequest, "bytes must be 1..1MiB")
		return
	}
	transport := r.URL.Query().Get("transport")
	if transport == "" {
		transport = "quic"
	}
	if transport != "tcp" && transport != "quic" {
		writeErr(w, http.StatusBadRequest, "transport must be tcp|quic")
		return
	}
	host := r.URL.Query().Get("target-host")
	if host == "" {
		host = "10.0.2.2"
	}

	type result struct {
		Index int    `json:"index"`
		OK    bool   `json:"ok"`
		Error string `json:"error,omitempty"`
		Bytes int    `json:"bytes"`
	}
	results := make([]result, count)
	pumpCtx, cancel := context.WithTimeout(ctx, 120*time.Second)
	defer cancel()

	var wg sync.WaitGroup
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			payload := make([]byte, n)
			if _, e := rand.Read(payload); e != nil {
				results[idx] = result{Index: idx, Error: "rand: " + e.Error()}
				return
			}
			sentHash := sha256.Sum256(payload)
			var recv []byte
			var rerr error
			switch transport {
			case "tcp":
				recv, rerr = roundtripTCP(pumpCtx, hub, host, hub.echoPort, payload)
			case "quic":
				recv, rerr = roundtripQUIC(pumpCtx, hub, host, hub.echoPort, payload)
			}
			r := result{Index: idx, Bytes: len(recv)}
			if rerr != nil {
				r.Error = rerr.Error()
			} else {
				recvHash := sha256.Sum256(recv)
				r.OK = len(recv) == n && recvHash == sentHash
				if !r.OK {
					r.Error = "byte/length mismatch"
				}
			}
			results[idx] = r
		}(i)
	}
	wg.Wait()

	succeeded := 0
	for _, r := range results {
		if r.OK {
			succeeded++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":        succeeded == count,
		"total":     count,
		"succeeded": succeeded,
		"transport": transport,
		"bytes":     n,
		"results":   results,
	})
}

func roundtripTCP(ctx context.Context, hub *Hub, host string, port int, payload []byte) ([]byte, error) {
	token, err := randomHexToken()
	if err != nil {
		return nil, err
	}
	ch, err := hub.sendOpenCommand(token, host, port)
	if err != nil {
		return nil, err
	}
	var sock net.Conn
	select {
	case res := <-ch:
		if res.fail != "" {
			// Agent dialed the target and got a refusal/timeout — production
			// surfaces this as an error to the OpenTunnel caller, we mirror.
			return nil, fmt.Errorf("agent OPEN_FAIL: %s", res.fail)
		}
		sock = res.sock
	case <-ctx.Done():
		hub.dropPending(token)
		return nil, errors.New("timed out waiting for data socket")
	}
	defer sock.Close()
	return pumpFullDuplex(ctx, sock, sock, payload)
}

func roundtripQUIC(ctx context.Context, hub *Hub, host string, port int, payload []byte) ([]byte, error) {
	stream, err := hub.openQUICTunnel(ctx, host, port)
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

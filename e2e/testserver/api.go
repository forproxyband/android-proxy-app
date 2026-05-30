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

// modeTargetPort picks the right internal target listener for the test
// mode. Tests can also override via target-port query — that path stays
// for error-injection scenarios (unreachable ports etc.).
func modeTargetPort(hub *Hub, mode string) (int, error) {
	switch mode {
	case "echo", "":
		return hub.echoPort, nil
	case "upload":
		return hub.sinkPort, nil
	case "download":
		return hub.sourcePort, nil
	}
	return 0, fmt.Errorf("bad mode %q (echo|upload|download)", mode)
}

// handleTunnelRoundtrip drives one tunnel through the connected agent
// in one of three modes:
//
//	echo     full-duplex: write payload, read it back, hash-compare
//	upload   write-only:  push payload into sink target, no return read
//	download read-only:   drain N bytes from source target, no write
//
// Upload + download exist because a real production bug had QUIC upload
// hanging while download worked fine — full-duplex echo masks
// direction-specific stalls because reverse traffic keeps refreshing
// flow-control credit on the broken side.
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
	mode := r.URL.Query().Get("mode")
	if mode == "" {
		mode = "echo"
	}

	host := r.URL.Query().Get("target-host")
	if host == "" {
		host = "10.0.2.2" // Android emulator → host loopback
	}
	// target-port: default depends on mode (echo / sink / source). Tests
	// for error paths can override with an unreachable port.
	targetPort, perr := modeTargetPort(hub, mode)
	if perr != nil {
		writeErr(w, http.StatusBadRequest, perr.Error())
		return
	}
	if p := r.URL.Query().Get("target-port"); p != "" {
		v, perr := strconv.Atoi(p)
		if perr != nil || v <= 0 || v > 65535 {
			writeErr(w, http.StatusBadRequest, "bad target-port="+p)
			return
		}
		targetPort = v
	}

	// Generate payload only when the mode actually sends one. For
	// download we just need an empty buffer — the bytes will come back
	// from the source target, mock doesn't supply them.
	var payload []byte
	var sentHashHex string
	if mode == "echo" || mode == "upload" {
		payload = make([]byte, n)
		if _, err := rand.Read(payload); err != nil {
			writeErr(w, http.StatusInternalServerError, "rand: "+err.Error())
			return
		}
		h := sha256.Sum256(payload)
		sentHashHex = hex.EncodeToString(h[:])
	}

	// Timeout is generous: 16 MiB through a QEMU-emulated x86_64 QUIC
	// stack is bounded by software emulation, not by network speed.
	pumpCtx, cancel := context.WithTimeout(ctx, 120*time.Second)
	defer cancel()

	recv, sent, err := runTunnel(pumpCtx, hub, transport, host, targetPort, mode, payload, n)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":         false,
			"error":      err.Error(),
			"bytes":      n,
			"transport":  transport,
			"mode":       mode,
			"sent_bytes": sent,
			"recv_bytes": len(recv),
		})
		return
	}
	ok := false
	var recvHashHex string
	switch mode {
	case "echo":
		h := sha256.Sum256(recv)
		recvHashHex = hex.EncodeToString(h[:])
		ok = len(recv) == n && recvHashHex == sentHashHex
	case "upload":
		ok = sent == n
	case "download":
		ok = len(recv) == n
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":         ok,
		"bytes":      n,
		"sent_bytes": sent,
		"recv_bytes": len(recv),
		"transport":  transport,
		"mode":       mode,
		"hash_sent":  sentHashHex,
		"hash_recv":  recvHashHex,
	})
}

// handleTunnelConcurrent fans out `count` parallel round-trips through
// `count` separate tunnels and reports the aggregate, including
// throughput metrics suitable for speedtest-style measurement. For QUIC
// this exercises stream multiplexing on a single connection; for TCP it
// exercises the data-socket warm pool + token matchmaking under
// contention.
//
// Each tunnel runs full-duplex (pumpFullDuplex writes payload while
// reading the echo back concurrently), matching how speedtest probes a
// link's actual capacity rather than its half-duplex throughput.
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
	// Per-tunnel cap raised from 1 MiB to 16 MiB so multi-connection
	// throughput tests can push enough bytes to amortize handshake +
	// ramp-up and report a stable Mbps number.
	if err != nil || n <= 0 || n > 16<<20 {
		writeErr(w, http.StatusBadRequest, "bytes must be 1..16MiB")
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
	mode := r.URL.Query().Get("mode")
	if mode == "" {
		mode = "echo"
	}
	host := r.URL.Query().Get("target-host")
	if host == "" {
		host = "10.0.2.2"
	}
	targetPort, perr := modeTargetPort(hub, mode)
	if perr != nil {
		writeErr(w, http.StatusBadRequest, perr.Error())
		return
	}

	type result struct {
		Index      int     `json:"index"`
		OK         bool    `json:"ok"`
		Error      string  `json:"error,omitempty"`
		SentBytes  int     `json:"sent_bytes"`
		RecvBytes  int     `json:"recv_bytes"`
		DurationMs int64   `json:"duration_ms"`
		Mbps       float64 `json:"mbps"` // one-way Mbps over n
	}
	results := make([]result, count)
	// 240 s ceiling — 16 tunnels × 16 MiB through a software-emulated
	// QUIC stack can take a while on ubuntu-latest under load.
	pumpCtx, cancel := context.WithTimeout(ctx, 240*time.Second)
	defer cancel()

	wallStart := time.Now()
	var wg sync.WaitGroup
	for i := 0; i < count; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			// Payload only when the mode actually sends bytes.
			var payload []byte
			var sentHash [32]byte
			if mode == "echo" || mode == "upload" {
				payload = make([]byte, n)
				if _, e := rand.Read(payload); e != nil {
					results[idx] = result{Index: idx, Error: "rand: " + e.Error()}
					return
				}
				sentHash = sha256.Sum256(payload)
			}
			tStart := time.Now()
			recv, sent, rerr := runTunnel(pumpCtx, hub, transport, host, targetPort, mode, payload, n)
			elapsed := time.Since(tStart)
			r := result{
				Index:      idx,
				SentBytes:  sent,
				RecvBytes:  len(recv),
				DurationMs: elapsed.Milliseconds(),
			}
			if rerr != nil {
				r.Error = rerr.Error()
			} else {
				switch mode {
				case "echo":
					recvHash := sha256.Sum256(recv)
					r.OK = len(recv) == n && recvHash == sentHash
				case "upload":
					r.OK = sent == n
				case "download":
					r.OK = len(recv) == n
				}
				if !r.OK && r.Error == "" {
					r.Error = "byte/length mismatch"
				}
			}
			if elapsed > 0 {
				r.Mbps = float64(n) * 8.0 / elapsed.Seconds() / 1_000_000.0
			}
			results[idx] = r
		}(i)
	}
	wg.Wait()
	wallElapsed := time.Since(wallStart)

	succeeded := 0
	for _, r := range results {
		if r.OK {
			succeeded++
		}
	}
	// Aggregate one-way Mbps: each successful tunnel carried n bytes in
	// the *primary* direction (echo and upload count payload write;
	// download counts target→mock read). For echo the link also carried
	// the same bytes back, hence agg_mbps_duplex = 2× agg_mbps.
	var aggBytes int
	for _, r := range results {
		if r.OK {
			aggBytes += n
		}
	}
	aggMbps := 0.0
	if wallElapsed > 0 {
		aggMbps = float64(aggBytes) * 8.0 / wallElapsed.Seconds() / 1_000_000.0
	}
	duplexMbps := aggMbps
	if mode == "echo" {
		duplexMbps = aggMbps * 2
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"ok":              succeeded == count,
		"total":           count,
		"succeeded":       succeeded,
		"transport":       transport,
		"mode":            mode,
		"bytes":           n,
		"wall_ms":         wallElapsed.Milliseconds(),
		"agg_bytes":       aggBytes,
		"agg_mbps":        aggMbps,
		"agg_mbps_duplex": duplexMbps,
		"results":         results,
	})
}

// runTunnel opens a tunnel on the chosen transport and pumps bytes per
// the chosen mode. Returns (received, sent, err). For download mode the
// caller passes the desired byte count in `n` and a nil payload.
func runTunnel(
	ctx context.Context,
	hub *Hub,
	transport, host string,
	port int,
	mode string,
	payload []byte,
	n int,
) (recv []byte, sent int, err error) {
	switch transport {
	case "tcp":
		return runTunnelTCP(ctx, hub, host, port, mode, payload, n)
	case "quic":
		return runTunnelQUIC(ctx, hub, host, port, mode, payload, n)
	}
	return nil, 0, fmt.Errorf("transport must be tcp|quic, got %q", transport)
}

func runTunnelTCP(
	ctx context.Context, hub *Hub, host string, port int,
	mode string, payload []byte, n int,
) ([]byte, int, error) {
	token, err := randomHexToken()
	if err != nil {
		return nil, 0, err
	}
	ch, err := hub.sendOpenCommand(token, host, port)
	if err != nil {
		return nil, 0, err
	}
	var sock net.Conn
	select {
	case res := <-ch:
		if res.fail != "" {
			return nil, 0, fmt.Errorf("agent OPEN_FAIL: %s", res.fail)
		}
		sock = res.sock
	case <-ctx.Done():
		hub.dropPending(token)
		return nil, 0, errors.New("timed out waiting for data socket")
	}
	defer sock.Close()
	return runPump(ctx, sock, sock, mode, payload, n)
}

func runTunnelQUIC(
	ctx context.Context, hub *Hub, host string, port int,
	mode string, payload []byte, n int,
) ([]byte, int, error) {
	stream, err := hub.openQUICTunnel(ctx, host, port)
	if err != nil {
		return nil, 0, err
	}
	defer stream.Close()
	return runPump(ctx, stream, stream, mode, payload, n)
}

// runPump dispatches to the directional pump matching mode. Read/write
// sides are separate parameters so QUIC stream and TCP socket — both
// io.Reader+io.Writer on the same object — pass through cleanly.
func runPump(
	ctx context.Context, r io.Reader, w io.Writer,
	mode string, payload []byte, n int,
) ([]byte, int, error) {
	switch mode {
	case "echo", "":
		recv, err := pumpFullDuplex(ctx, r, w, payload)
		return recv, len(payload), err
	case "upload":
		sent, err := pumpUpload(ctx, w, payload)
		return nil, sent, err
	case "download":
		recv, err := pumpDownload(ctx, r, n)
		return recv, 0, err
	}
	return nil, 0, fmt.Errorf("bad mode %q", mode)
}

// pumpUpload writes the payload into w and returns when the write is
// done. No reads from the reverse direction — this is what catches
// upload-side hangs that an echo test would mask. Caller's defer closes
// the underlying conn / stream; the EOF on the agent side then tears
// down the target socket so the sink's io.Copy returns.
func pumpUpload(ctx context.Context, w io.Writer, payload []byte) (int, error) {
	done := make(chan struct{})
	var (
		nWritten int
		err      error
	)
	go func() {
		nWritten, err = w.Write(payload)
		close(done)
	}()
	select {
	case <-done:
		return nWritten, err
	case <-ctx.Done():
		return nWritten, ctx.Err()
	}
}

// pumpDownload reads exactly n bytes from r (the source target streams
// random bytes through the tunnel). Stops as soon as we have enough —
// the deferred close on the caller side signals the source to stop.
func pumpDownload(ctx context.Context, r io.Reader, n int) ([]byte, error) {
	done := make(chan struct{})
	buf := make([]byte, n)
	var err error
	go func() {
		_, err = io.ReadFull(r, buf)
		close(done)
	}()
	select {
	case <-done:
		if err != nil {
			return nil, err
		}
		return buf, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
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

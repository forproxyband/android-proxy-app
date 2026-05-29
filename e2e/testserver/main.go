// Mock proxy upstream for the Android agent's e2e tests. See README.md.
//
// Wire protocol matches the constants in
// app/src/main/java/com/proxyagent/app/nativeagent/NativeProxyAgent.kt
// companion object — keep them in lock-step if either side moves.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"

	"github.com/quic-go/quic-go"
)

// Wire constants — see NativeProxyAgent.kt companion object.
var wireMagic = [4]byte{'T', 'U', 'N', 'L'}

const (
	wireVersion     byte = 1
	connTypeControl byte = 0x01
	connTypeData    byte = 0x02
	tokenHexLen          = 32 // 16 raw bytes hex-encoded on the wire
	quicALPN             = "proxy-tunnel/1"
)

// Hub holds shared state between the TCP / QUIC / API loops. One mock
// process supports at most one connected agent at a time — adequate for
// CI, simpler than a multi-tenant table.
type Hub struct {
	bindAddr string
	echoPort int
	authKey  string // empty = accept any

	mu sync.Mutex

	// Active TCP control: receives OPEN commands from the API.
	tcpCtrl *tcpControl

	// Active QUIC control: API opens server-initiated streams on this conn.
	quicConn quic.Connection

	// TCP tunnel matchmaking: key = 32-char hex token, value receives one
	// of:
	//   - {sock: <conn>, fail: ""}  → agent's data socket arrived
	//   - {sock: nil,   fail: <r>}  → agent sent OPEN_FAIL with reason
	// The API handler registers the channel before sending OPEN and reads
	// from it after. Closing the channel without a send is not used —
	// always deliver a result so the API handler can branch cleanly.
	pending map[string]chan pendingResult

	// Liveness flags — /healthz waits for both loops to be live before
	// returning 200, so tests can poll without a race on startup.
	tcpReady  atomic.Bool
	quicReady atomic.Bool
}

// pendingResult is what the API handler waits on while the agent is
// resolving an OPEN. Exactly one of sock or fail is meaningful: a
// non-nil sock means the data socket arrived with a matching token,
// and a non-empty fail means the agent reported OPEN_FAIL with that
// reason instead.
type pendingResult struct {
	sock net.Conn
	fail string
}

func newHub(bind string, echoPort int, authKey string) *Hub {
	return &Hub{
		bindAddr: bind,
		echoPort: echoPort,
		authKey:  authKey,
		pending:  make(map[string]chan pendingResult),
	}
}

func (h *Hub) registerPending(token string) chan pendingResult {
	ch := make(chan pendingResult, 1)
	h.mu.Lock()
	h.pending[token] = ch
	h.mu.Unlock()
	return ch
}

func (h *Hub) takePending(token string) chan pendingResult {
	h.mu.Lock()
	ch := h.pending[token]
	delete(h.pending, token)
	h.mu.Unlock()
	return ch
}

func (h *Hub) dropPending(token string) {
	h.mu.Lock()
	delete(h.pending, token)
	h.mu.Unlock()
}

// failPending is the mock counterpart of production
// `Registry.deliverDataConn(token, nil)` — wakes the API handler with
// the OPEN_FAIL reason so the test surfaces a clean error instead of
// timing out. No-op if the token has already been resolved or evicted.
func (h *Hub) failPending(token, reason string) {
	h.mu.Lock()
	ch := h.pending[token]
	delete(h.pending, token)
	h.mu.Unlock()
	if ch == nil {
		return
	}
	select {
	case ch <- pendingResult{fail: reason}:
	default:
	}
}

func main() {
	var (
		bindAddr = flag.String("bind", "0.0.0.0", "bind address for all listeners")
		// Production registrators listen for TCP and UDP/QUIC on the same
		// port (startTcp + startQuic in NativeProxyAgent.kt both use
		// creds.port). Defaulting both flags to 17080 matches that — TCP
		// and UDP are independent sockets, no conflict.
		tcpPort  = flag.Int("tcp-port", 17080, "TCP uplink port")
		quicPort = flag.Int("quic-port", 17080, "UDP/QUIC uplink port")
		echoPort = flag.Int("echo-port", 17082, "internal TCP echo target port")
		apiPort  = flag.Int("api-port", 17083, "HTTP test API port")
		authKey  = flag.String("auth-key", "e2e", `expected client AUTH "key" field; empty = accept any`)
	)
	flag.Parse()

	log.SetFlags(log.LstdFlags | log.Lmicroseconds)

	hub := newHub(*bindAddr, *echoPort, *authKey)

	tlsCfg, err := buildTLSConfig()
	if err != nil {
		log.Fatalf("tls cert: %v", err)
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	// Each goroutine logs its own listen line on success; the program exits
	// on context cancel (any listener's failure is logged but does not bring
	// the whole process down — CI artifacts pick up logs for diagnosis).
	var wg sync.WaitGroup
	wg.Add(4)
	go func() { defer wg.Done(); runEcho(ctx, hub, *echoPort) }()
	go func() { defer wg.Done(); runTCP(ctx, hub, *tcpPort) }()
	go func() { defer wg.Done(); runQUIC(ctx, hub, *quicPort, tlsCfg) }()
	go func() { defer wg.Done(); runAPI(ctx, hub, *apiPort) }()

	log.Printf("testserver up: bind=%s tcp=%d quic=%d echo=%d api=%d auth-key=%q",
		*bindAddr, *tcpPort, *quicPort, *echoPort, *apiPort, *authKey)

	<-ctx.Done()
	log.Println("shutdown signal received")
	wg.Wait()
}

// listenTCP wraps net.Listen with a clearer fatal log on bind failure.
func listenTCP(ctx context.Context, addr string) (net.Listener, error) {
	var lc net.ListenConfig
	return lc.Listen(ctx, "tcp", addr)
}

func addrStr(bind string, port int) string {
	return fmt.Sprintf("%s:%d", bind, port)
}

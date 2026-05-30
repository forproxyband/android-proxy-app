package main

import (
	"context"
	"crypto/rand"
	"io"
	"log"
	"net"
)

// Three TCP targets — the agent dials one of these depending on the test
// mode the API handler chose:
//
//   echo    — full-duplex byte-echo. Catches byte-corruption in either
//             direction but cannot tell which direction stalled.
//   sink    — reads and discards. Tests *pure upload* (mock → agent →
//             target). If the upload direction hangs in isolation
//             (e.g. QUIC flow-control accounting bug that only shows up
//             without reverse traffic), pumpUpload will time out.
//   source  — writes random bytes forever until the peer closes the
//             stream. Tests *pure download* (target → agent → mock).
//             Mock reads until it has the requested N bytes, then
//             closes — that closure triggers the agent to tear down
//             the tunnel cleanly.
//
// The split exists because we have hit a real production bug where QUIC
// download worked but upload hung; an echo-only test would not have
// caught it because reverse traffic was happily refreshing flow-control
// credit in the broken direction.

func runEcho(ctx context.Context, hub *Hub, port int) {
	runTarget(ctx, hub, port, "echo", func(c net.Conn) {
		_, _ = io.Copy(c, c)
	})
}

func runSink(ctx context.Context, hub *Hub, port int) {
	runTarget(ctx, hub, port, "sink", func(c net.Conn) {
		_, _ = io.Copy(io.Discard, c)
	})
}

func runSource(ctx context.Context, hub *Hub, port int) {
	runTarget(ctx, hub, port, "source", func(c net.Conn) {
		// Stream random bytes until the peer (agent) closes. 64 KiB
		// chunks balance kernel write cost vs goroutine scheduling.
		// rand.Read is acceptable here — speed isn't critical and it
		// avoids the test caring about content beyond byte counts.
		buf := make([]byte, 64*1024)
		for {
			if _, err := rand.Read(buf); err != nil {
				return
			}
			if _, err := c.Write(buf); err != nil {
				return
			}
		}
	})
}

// runTarget is the shared accept loop for echo / sink / source.
func runTarget(ctx context.Context, hub *Hub, port int, label string, handler func(net.Conn)) {
	ln, err := listenTCP(ctx, addrStr(hub.bindAddr, port))
	if err != nil {
		log.Printf("%s listen: %v", label, err)
		return
	}
	defer ln.Close()
	log.Printf("%s: listening on %s", label, ln.Addr())

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
			log.Printf("%s accept: %v", label, err)
			continue
		}
		go func(c net.Conn) {
			defer c.Close()
			handler(c)
		}(c)
	}
}

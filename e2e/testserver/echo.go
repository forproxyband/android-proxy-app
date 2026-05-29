package main

import (
	"context"
	"io"
	"log"
	"net"
)

// runEcho is the TCP target the agent dials when we open a tunnel. Every
// byte the agent writes comes back unchanged — that's the round-trip
// assertion the API drives in the tests.
func runEcho(ctx context.Context, hub *Hub, port int) {
	ln, err := listenTCP(ctx, addrStr(hub.bindAddr, port))
	if err != nil {
		log.Printf("echo listen: %v", err)
		return
	}
	defer ln.Close()
	log.Printf("echo: listening on %s", ln.Addr())

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
			log.Printf("echo accept: %v", err)
			continue
		}
		go func(c net.Conn) {
			defer c.Close()
			_, _ = io.Copy(c, c)
		}(c)
	}
}

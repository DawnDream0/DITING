// packet_pipe.go implements a bounded, bidirectional in-memory packet pipe bridging the TUN interceptor
// and the userspace gVisor TCP/IP stack.
//
// Concurrency & Teardown Design:
// - Ownership: DnsInterceptor retains exclusive read access to the TUN fd, pushing non-DNS packets into the pipe.
// - Backpressure & Loss: Enforces bounded queues; overflows drop packets silently, relying on TCP retransmissions.
// - Panic-Free Shutdown: Uses atomic flags and sync.Once to unblock pending Read and Pop operations without closing
//   active data channels, eliminating send-on-closed-channel panics.

package tunnel

import (
	"io"
	"sync"
	"sync/atomic"
)

const (
	packetQueueDepth = 1024
)

type packetPipe struct {
	inbound  chan []byte
	outbound chan []byte

	done     chan struct{}
	doneOnce sync.Once

	inboundDropped  atomic.Int64
	outboundDropped atomic.Int64
	outboundWritten atomic.Int64
}

func newPacketPipe() *packetPipe {
	return &packetPipe{
		inbound:  make(chan []byte, packetQueueDepth),
		outbound: make(chan []byte, packetQueueDepth),
		done:     make(chan struct{}),
	}
}

const pipePooledMaxPacketBytes = 2 * defaultTunMTU

var pipePacketPool = sync.Pool{
	New: func() any {
		return make([]byte, defaultTunMTU)
	},
}

func (p *packetPipe) Read(buf []byte) (int, error) {
	select {
	case pkt := <-p.inbound:
		n := copy(buf, pkt)
		if cap(pkt) <= pipePooledMaxPacketBytes && cap(pkt) >= defaultTunMTU {
			pipePacketPool.Put(pkt[:0])
		}
		return n, nil
	case <-p.done:
		return 0, io.EOF
	}
}

func (p *packetPipe) Write(buf []byte) (int, error) {
	var pkt []byte
	pooled := false
	if len(buf) <= pipePooledMaxPacketBytes {
		pooledBuf := pipePacketPool.Get().([]byte)
		pkt = append(pooledBuf[:0], buf...)
		pooled = true
	} else {
		pkt = make([]byte, len(buf))
		copy(pkt, buf)
	}

	select {
	case <-p.done:
		if pooled {
			pipePacketPool.Put(pkt[:0])
		}
		return len(buf), nil
	default:
	}
	select {
	case p.outbound <- pkt:
		c := p.outboundWritten.Add(1)
		if c <= 5 {
			logf("packetPipe: outbound write #%d (size=%d)", c, len(buf))
		}
	case <-p.done:
		if pooled {
			pipePacketPool.Put(pkt[:0])
		}
	default:
		if pooled {
			pipePacketPool.Put(pkt[:0])
		}

		c := p.outboundDropped.Add(1)
		if c <= 3 {
			logf("packetPipe: outbound DROPPED #%d (queue full, size=%d)", c, len(buf))
		}
	}
	return len(buf), nil
}

func (p *packetPipe) Push(pkt []byte) {
	select {
	case <-p.done:
		return
	default:
	}

	var buf []byte
	pooled := false
	if len(pkt) <= pipePooledMaxPacketBytes {
		pooledBuf := pipePacketPool.Get().([]byte)
		buf = append(pooledBuf[:0], pkt...)
		pooled = true
	} else {
		buf = make([]byte, len(pkt))
		copy(buf, pkt)
	}

	select {
	case p.inbound <- buf:
	case <-p.done:
		if pooled {
			pipePacketPool.Put(buf[:0])
		}
	default:
		if pooled {
			pipePacketPool.Put(buf[:0])
		}

		c := p.inboundDropped.Add(1)
		if c <= 3 {
			logf("packetPipe: inbound DROPPED #%d (queue full, size=%d)", c, len(pkt))
		}
	}
}

func (p *packetPipe) Pop() []byte {
	select {
	case pkt := <-p.outbound:
		return pkt
	case <-p.done:
		return nil
	}
}

func (p *packetPipe) Close() {
	p.doneOnce.Do(func() { close(p.done) })
}

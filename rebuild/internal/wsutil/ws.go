// Package wsutil is a tiny, dependency-free WebSocket server (RFC 6455, the
// subset we need: text/binary data frames, ping/pong, close). Browsers are the
// clients, so only server-side framing is implemented. No external module.
package wsutil

import (
	"bufio"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
)

const acceptMagic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

type Conn struct {
	conn net.Conn
	rw   *bufio.ReadWriter
	wmu  sync.Mutex
}

func Upgrade(w http.ResponseWriter, r *http.Request) (*Conn, error) {
	if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
		return nil, errors.New("not a websocket upgrade")
	}
	key := r.Header.Get("Sec-WebSocket-Key")
	if key == "" {
		return nil, errors.New("missing Sec-WebSocket-Key")
	}
	hj, ok := w.(http.Hijacker)
	if !ok {
		return nil, errors.New("response writer is not a hijacker")
	}
	conn, rw, err := hj.Hijack()
	if err != nil {
		return nil, err
	}
	h := sha1.New()
	h.Write([]byte(key + acceptMagic))
	accept := base64.StdEncoding.EncodeToString(h.Sum(nil))
	resp := "HTTP/1.1 101 Switching Protocols\r\n" +
		"Upgrade: websocket\r\n" +
		"Connection: Upgrade\r\n" +
		"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
	if _, err := rw.WriteString(resp); err != nil {
		conn.Close()
		return nil, err
	}
	if err := rw.Flush(); err != nil {
		conn.Close()
		return nil, err
	}
	return &Conn{conn: conn, rw: rw}, nil
}

// ReadMessage returns the next text/binary message payload, handling control
// frames internally.
func (c *Conn) ReadMessage() ([]byte, error) {
	for {
		b0, err := c.rw.ReadByte()
		if err != nil {
			return nil, err
		}
		opcode := b0 & 0x0f
		b1, err := c.rw.ReadByte()
		if err != nil {
			return nil, err
		}
		masked := b1&0x80 != 0
		length := int(b1 & 0x7f)
		switch length {
		case 126:
			var ext [2]byte
			if _, err := io.ReadFull(c.rw, ext[:]); err != nil {
				return nil, err
			}
			length = int(binary.BigEndian.Uint16(ext[:]))
		case 127:
			var ext [8]byte
			if _, err := io.ReadFull(c.rw, ext[:]); err != nil {
				return nil, err
			}
			length = int(binary.BigEndian.Uint64(ext[:]))
		}
		var mask [4]byte
		if masked {
			if _, err := io.ReadFull(c.rw, mask[:]); err != nil {
				return nil, err
			}
		}
		payload := make([]byte, length)
		if _, err := io.ReadFull(c.rw, payload); err != nil {
			return nil, err
		}
		if masked {
			for i := 0; i < length; i++ {
				payload[i] ^= mask[i%4]
			}
		}
		switch opcode {
		case 0x1, 0x2: // text, binary
			return payload, nil
		case 0x8: // close
			c.Close()
			return nil, io.EOF
		case 0x9: // ping -> pong
			_ = c.writeFrame(0xA, payload)
		case 0xA: // pong, ignore
		default: // ignore unsupported (continuation, etc.)
		}
	}
}

func (c *Conn) WriteMessage(data []byte) error { return c.writeFrame(0x1, data) }

func (c *Conn) writeFrame(opcode byte, data []byte) error {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	b0 := byte(0x80) | opcode // FIN + opcode
	n := len(data)
	var hdr []byte
	switch {
	case n < 126:
		hdr = []byte{b0, byte(n)}
	case n <= 0xffff:
		hdr = []byte{b0, 126, byte(n >> 8), byte(n)}
	default:
		hdr = make([]byte, 10)
		hdr[0] = b0
		hdr[1] = 127
		binary.BigEndian.PutUint64(hdr[2:], uint64(n))
	}
	if _, err := c.rw.Write(hdr); err != nil {
		return err
	}
	if _, err := c.rw.Write(data); err != nil {
		return err
	}
	return c.rw.Flush()
}

func (c *Conn) Close() error { return c.conn.Close() }

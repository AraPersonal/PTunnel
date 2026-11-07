package tun

import (
	"io"
)

// Device represents a generic cross-platform TUN interface.
type Device interface {
	io.ReadWriteCloser
	Name() string
	MTU() int
}

// Config specifies initialization parameters for the TUN device.
type Config struct {
	Name    string
	Address string
	MTU     int
	Routes  []string
	DNS     []string
}

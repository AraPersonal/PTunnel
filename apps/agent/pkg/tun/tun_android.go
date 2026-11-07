package tun

import (
	"fmt"
	"os"
)

// AndroidTun wraps a Linux/Android file descriptor provided by Android's VpnService.
type AndroidTun struct {
	file *os.File
	mtu  int
}

// FromFd creates a TUN device from an existing integer file descriptor.
func FromFd(fd int, mtu int) (*AndroidTun, error) {
	if fd <= 0 {
		return nil, fmt.Errorf("invalid file descriptor: %d", fd)
	}

	f := os.NewFile(uintptr(fd), "tun")
	if f == nil {
		return nil, fmt.Errorf("failed to open os.File from fd %d", fd)
	}

	if mtu <= 0 {
		mtu = 1500
	}

	return &AndroidTun{
		file: f,
		mtu:  mtu,
	}, nil
}

func (t *AndroidTun) Read(b []byte) (int, error) {
	return t.file.Read(b)
}

func (t *AndroidTun) Write(b []byte) (int, error) {
	return t.file.Write(b)
}

func (t *AndroidTun) Close() error {
	return t.file.Close()
}

func (t *AndroidTun) Name() string {
	return "android-tun"
}

func (t *AndroidTun) MTU() int {
	return t.mtu
}

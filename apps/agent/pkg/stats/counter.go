package stats

import (
	"sync/atomic"
	"time"
)

// TrafficCounter provides lock-free atomic tracking for tunnel traffic.
type TrafficCounter struct {
	uplinkBytes   atomic.Int64
	downlinkBytes atomic.Int64
	startedAt     time.Time
}

// NewTrafficCounter creates a new initialized counter.
func NewTrafficCounter() *TrafficCounter {
	return &TrafficCounter{
		startedAt: time.Now(),
	}
}

// AddUplink adds n bytes to the uplink counter.
func (c *TrafficCounter) AddUplink(n int64) {
	c.uplinkBytes.Add(n)
}

// AddDownlink adds n bytes to the downlink counter.
func (c *TrafficCounter) AddDownlink(n int64) {
	c.downlinkBytes.Add(n)
}

// Uplink returns current total uplink bytes.
func (c *TrafficCounter) Uplink() int64 {
	return c.uplinkBytes.Load()
}

// Downlink returns current total downlink bytes.
func (c *TrafficCounter) Downlink() int64 {
	return c.downlinkBytes.Load()
}

// Uptime returns the elapsed seconds since started.
func (c *TrafficCounter) Uptime() int64 {
	return int64(time.Since(c.startedAt).Seconds())
}

// Reset resets counters and startedAt.
func (c *TrafficCounter) Reset() {
	c.uplinkBytes.Store(0)
	c.downlinkBytes.Store(0)
	c.startedAt = time.Now()
}

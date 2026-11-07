package stats

import (
	"testing"
)

func TestTrafficCounter(t *testing.T) {
	c := NewTrafficCounter()

	c.AddUplink(1024)
	c.AddDownlink(2048)

	if got := c.Uplink(); got != 1024 {
		t.Errorf("Uplink() = %d; want 1024", got)
	}

	if got := c.Downlink(); got != 2048 {
		t.Errorf("Downlink() = %d; want 2048", got)
	}

	c.Reset()

	if got := c.Uplink(); got != 0 {
		t.Errorf("Uplink() after Reset = %d; want 0", got)
	}
	if got := c.Downlink(); got != 0 {
		t.Errorf("Downlink() after Reset = %d; want 0", got)
	}
}

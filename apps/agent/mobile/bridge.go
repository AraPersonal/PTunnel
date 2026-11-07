package mobile

import (
	"github.com/AraPersonal/PTunnel/apps/agent/pkg/agent"
)

// PtunnelCore is the primary gomobile entry point for Android.
type PtunnelCore struct {
	agent *agent.Agent
}

// NewPtunnelCore creates an initialized core instance for Android.
func NewPtunnelCore() *PtunnelCore {
	return &PtunnelCore{
		agent: agent.NewAgent(),
	}
}

// Start begins the VPN tunnel with the provided configuration and Android tun file descriptor.
func (c *PtunnelCore) Start(config string, tunFd int) error {
	return c.agent.Start(config, tunFd)
}

// Stop terminates the active VPN tunnel.
func (c *PtunnelCore) Stop() error {
	return c.agent.Stop()
}

// MeasureDelay probes a test URL (e.g. https://www.gstatic.com/generate_204) and returns latency in ms.
// Returns -1 on failure.
func (c *PtunnelCore) MeasureDelay(testURL string) int64 {
	ms, err := c.agent.MeasureDelay(testURL)
	if err != nil {
		return -1
	}
	return ms
}

// CurrentState returns the state as a string.
func (c *PtunnelCore) CurrentState() string {
	return string(c.agent.CurrentState())
}

// UplinkBytes returns total sent bytes.
func (c *PtunnelCore) UplinkBytes() int64 {
	return c.agent.GetStats().UplinkBytes
}

// DownlinkBytes returns total received bytes.
func (c *PtunnelCore) DownlinkBytes() int64 {
	return c.agent.GetStats().DownlinkBytes
}

package agent

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/AraPersonal/PTunnel/apps/agent/pkg/engine"
	"github.com/AraPersonal/PTunnel/apps/agent/pkg/probe"
	"github.com/AraPersonal/PTunnel/apps/agent/pkg/stats"
)

// Agent manages the complete lifecycle of the client tunnel.
type Agent struct {
	mu            sync.RWMutex
	state         State
	counter       *stats.TrafficCounter
	activeEngine  engine.Engine
	engines       map[string]engine.Engine
	prober        *probe.Prober
	cancelSession context.CancelFunc
}

// ConfigProfile is a minimal helper to detect engine type from JSON.
type ConfigProfile struct {
	Transport string `json:"transport"`
	Tag       string `json:"tag"`
}

// NewAgent creates a new unified client Agent.
func NewAgent() *Agent {
	return &Agent{
		state:   StateDisconnected,
		counter: stats.NewTrafficCounter(),
		engines: make(map[string]engine.Engine),
		prober:  probe.NewProber(""),
	}
}

// RegisterEngine registers an available proxy backend.
func (a *Agent) RegisterEngine(name string, e engine.Engine) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.engines[name] = e
}

// CurrentState returns the current connection state.
func (a *Agent) CurrentState() State {
	a.mu.RLock()
	defer a.mu.RUnlock()
	return a.state
}

// SetState safely transitions to a new state.
func (a *Agent) SetState(s State) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.state = s
}

// Start begins a tunnel session with the provided configuration and TUN handle.
func (a *Agent) Start(configJSON string, tunFd int) error {
	a.mu.Lock()
	if a.state == StateConnected || a.state == StateConnecting {
		a.mu.Unlock()
		return fmt.Errorf("session already in flight (state: %s)", a.state)
	}

	a.state = StateConnecting
	a.counter.Reset()
	ctx, cancel := context.WithCancel(context.Background())
	a.cancelSession = cancel
	a.mu.Unlock()

	// Detect target engine from config
	selectedEngineName := a.detectEngine(configJSON)
	a.mu.RLock()
	eng, exists := a.engines[selectedEngineName]
	a.mu.RUnlock()

	if !exists {
		// Fallback to default or xray
		a.mu.RLock()
		eng = a.engines["xray"]
		a.mu.RUnlock()
		if eng == nil {
			a.SetState(StateDisconnected)
			return fmt.Errorf("engine '%s' not registered and no default available", selectedEngineName)
		}
	}

	a.mu.Lock()
	a.activeEngine = eng
	a.mu.Unlock()

	// Start engine
	err := eng.Start(ctx, configJSON, tunFd)
	if err != nil {
		a.SetState(StateDisconnected)
		return fmt.Errorf("failed to start engine %s: %w", eng.Name(), err)
	}

	// Update local prober proxy if available
	if proxyURL := eng.LocalProxyURL(); proxyURL != "" {
		a.mu.Lock()
		a.prober = probe.NewProber(proxyURL)
		a.mu.Unlock()
	}

	a.SetState(StateConnected)
	return nil
}

// Stop cleanly terminates the current session.
func (a *Agent) Stop() error {
	a.mu.Lock()
	defer a.mu.Unlock()

	if a.cancelSession != nil {
		a.cancelSession()
		a.cancelSession = nil
	}

	var err error
	if a.activeEngine != nil {
		err = a.activeEngine.Stop()
		a.activeEngine = nil
	}

	a.state = StateDisconnected
	return err
}

// GetStats returns the current telemetry snapshot.
func (a *Agent) GetStats() Stats {
	a.mu.RLock()
	defer a.mu.RUnlock()

	return Stats{
		UplinkBytes:   a.counter.Uplink(),
		DownlinkBytes: a.counter.Downlink(),
		UptimeSeconds: a.counter.Uptime(),
		LatencyMs:     0,
	}
}

// MeasureDelay probes a specific URL through the tunnel.
func (a *Agent) MeasureDelay(testURL string) (int64, error) {
	a.mu.RLock()
	p := a.prober
	a.mu.RUnlock()

	if p == nil {
		return -1, fmt.Errorf("prober not initialized")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	return p.MeasureHTTPDelay(ctx, testURL)
}

// detectEngine extracts the engine type from profile JSON.
func (a *Agent) detectEngine(rawJSON string) string {
	var profile ConfigProfile
	if err := json.Unmarshal([]byte(rawJSON), &profile); err == nil {
		trans := strings.ToLower(profile.Transport)
		tag := strings.ToLower(profile.Tag)
		if strings.Contains(trans, "hy2") || strings.Contains(trans, "hysteria") || strings.Contains(tag, "hy2") {
			return "hysteria2"
		}
	}
	return "xray"
}

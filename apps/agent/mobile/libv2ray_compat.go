package mobile

import (
	"sync"
)

// CoreCallbackHandler mirrors the callback interface expected by Android VpnService.
type CoreCallbackHandler interface {
	Startup() int64
	Shutdown() int64
	OnEmitStatus(code int64, message string) int64
}

// CoreController mirrors the controller methods used in ShieldVpnService.kt.
type CoreController struct {
	mu        sync.Mutex
	running   bool
	handler   CoreCallbackHandler
	core      *PtunnelCore
}

// InitCoreEnv initializes environment assets (noop compatibility stub).
func InitCoreEnv(assetPath string, base64Key string) {
	// Setup asset environment or GeoIP path if necessary.
}

// NewCoreController initializes a legacy-compatible CoreController.
func NewCoreController(handler CoreCallbackHandler) *CoreController {
	return &CoreController{
		handler: handler,
		core:    NewPtunnelCore(),
	}
}

// StartLoop starts the tunnel and invokes the startup callback.
func (c *CoreController) StartLoop(config string, tunFd int) int64 {
	c.mu.Lock()
	c.running = true
	c.mu.Unlock()

	if c.handler != nil {
		c.handler.Startup()
	}

	err := c.core.Start(config, tunFd)
	if err != nil {
		c.mu.Lock()
		c.running = false
		c.mu.Unlock()
		if c.handler != nil {
			c.handler.OnEmitStatus(-1, err.Error())
		}
		return -1
	}

	return 0
}

// StopLoop stops the running tunnel.
func (c *CoreController) StopLoop() int64 {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.running {
		return 0
	}

	_ = c.core.Stop()
	c.running = false

	if c.handler != nil {
		c.handler.Shutdown()
	}

	return 0
}

// MeasureDelay measures round-trip delay to a test URL.
func (c *CoreController) MeasureDelay(testURL string) int64 {
	return c.core.MeasureDelay(testURL)
}

// IsRunning reports whether the loop is currently active.
func (c *CoreController) IsRunning() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.running
}

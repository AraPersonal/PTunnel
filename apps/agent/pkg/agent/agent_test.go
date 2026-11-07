package agent

import (
	"context"
	"testing"
)

type dummyEngine struct {
	running bool
}

func (d *dummyEngine) Name() string {
	return "dummy"
}

func (d *dummyEngine) Start(ctx context.Context, configJSON string, tunFd int) error {
	d.running = true
	return nil
}

func (d *dummyEngine) Stop() error {
	d.running = false
	return nil
}

func (d *dummyEngine) IsRunning() bool {
	return d.running
}

func (d *dummyEngine) LocalProxyURL() string {
	return ""
}

func TestAgentLifecycle(t *testing.T) {
	ag := NewAgent()
	eng := &dummyEngine{}
	ag.RegisterEngine("dummy", eng)

	if got := ag.CurrentState(); got != StateDisconnected {
		t.Fatalf("expected state Disconnected, got %s", got)
	}

	err := ag.Start(`{"transport":"dummy"}`, 0)
	if err != nil {
		t.Fatalf("failed to start agent: %v", err)
	}

	if got := ag.CurrentState(); got != StateConnected {
		t.Fatalf("expected state Connected, got %s", got)
	}

	if !eng.IsRunning() {
		t.Fatal("expected engine to be running")
	}

	err = ag.Stop()
	if err != nil {
		t.Fatalf("failed to stop agent: %v", err)
	}

	if got := ag.CurrentState(); got != StateDisconnected {
		t.Fatalf("expected state Disconnected, got %s", got)
	}

	if eng.IsRunning() {
		t.Fatal("expected engine to be stopped")
	}
}

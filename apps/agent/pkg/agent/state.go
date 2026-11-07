package agent

// State represents the runtime state of the PTunnel core agent.
type State string

const (
	// StateDisconnected indicates no active tunnel is running.
	StateDisconnected State = "DISCONNECTED"
	// StateConnecting indicates the tunnel is dialing or probing.
	StateConnecting State = "CONNECTING"
	// StateConnected indicates traffic is actively verified and flowing through the tunnel.
	StateConnected State = "CONNECTED"
	// StateDegraded indicates the connection is slow or dropping probes but still alive.
	StateDegraded State = "DEGRADED"
	// StateRecovering indicates the agent is actively executing recovery rungs.
	StateRecovering State = "RECOVERING"
)

// Stats holds real-time telemetry from the tunnel.
type Stats struct {
	UplinkBytes   int64 `json:"uplinkBytes"`
	DownlinkBytes int64 `json:"downlinkBytes"`
	UptimeSeconds int64 `json:"uptimeSeconds"`
	LatencyMs     int64 `json:"latencyMs"`
}

package probe

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"sync"
	"time"
)

// Candidate represents a target endpoint to probe.
type Candidate struct {
	Address string // host:port
	Tag     string
}

// Result represents the probe outcome.
type Result struct {
	Candidate Candidate
	LatencyMs int64
	Err       error
}

// Prober provides fast, bounded connectivity probes.
type Prober struct {
	client *http.Client
}

// NewProber creates a Prober with sane timeouts.
func NewProber(proxyURL string) *Prober {
	transport := &http.Transport{
		DisableKeepAlives: true,
	}

	if proxyURL != "" {
		if parsed, err := url.Parse(proxyURL); err == nil {
			transport.Proxy = http.ProxyURL(parsed)
		}
	}

	return &Prober{
		client: &http.Client{
			Transport: transport,
			Timeout:   5 * time.Second,
		},
	}
}

// MeasureHTTPDelay sends a GET request to testUrl and returns the latency in milliseconds.
// Returns -1 on error.
func (p *Prober) MeasureHTTPDelay(ctx context.Context, testURL string) (int64, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, testURL, nil)
	if err != nil {
		return -1, err
	}

	start := time.Now()
	resp, err := p.client.Do(req)
	if err != nil {
		return -1, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode >= 400 {
		return -1, fmt.Errorf("unexpected status code: %d", resp.StatusCode)
	}

	return time.Since(start).Milliseconds(), nil
}

// FastTCPProbe measures TCP handshake latency in milliseconds.
// Returns -1 if unreachable within timeout.
func FastTCPProbe(ctx context.Context, address string, timeout time.Duration) (int64, error) {
	var d net.Dialer
	d.Timeout = timeout

	start := time.Now()
	conn, err := d.DialContext(ctx, "tcp", address)
	if err != nil {
		return -1, err
	}
	_ = conn.Close()

	return time.Since(start).Milliseconds(), nil
}

// RaceParallel probes multiple candidates simultaneously and returns the first healthy one,
// or sorted results if multiple succeed.
func RaceParallel(ctx context.Context, candidates []Candidate, timeout time.Duration) []Result {
	var wg sync.WaitGroup
	results := make([]Result, len(candidates))

	for i, c := range candidates {
		wg.Add(1)
		go func(idx int, cand Candidate) {
			defer wg.Done()
			probeCtx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()

			ms, err := FastTCPProbe(probeCtx, cand.Address, timeout)
			results[idx] = Result{
				Candidate: cand,
				LatencyMs: ms,
				Err:       err,
			}
		}(i, c)
	}

	wg.Wait()
	return results
}

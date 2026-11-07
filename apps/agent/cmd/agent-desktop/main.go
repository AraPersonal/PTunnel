package main

import (
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/AraPersonal/PTunnel/apps/agent/pkg/agent"
)

var (
	version = "0.1.0"
)

func main() {
	configPath := flag.String("config", "", "Path to the tunnel configuration file")
	probeURL := flag.String("probe", "", "Test probe a URL without starting the tunnel")
	showVersion := flag.Bool("version", false, "Show version")
	flag.Parse()

	if *showVersion {
		fmt.Printf("PTunnel Desktop Agent v%s\n", version)
		return
	}

	ag := agent.NewAgent()

	if *probeURL != "" {
		fmt.Printf("Probing URL: %s\n", *probeURL)
		ms, err := ag.MeasureDelay(*probeURL)
		if err != nil {
			log.Fatalf("Probe failed: %v", err)
		}
		fmt.Printf("Probe response time: %d ms\n", ms)
		return
	}

	if *configPath == "" {
		log.Println("Usage: ptunnel-desktop -config <path-to-config.json>")
		flag.PrintDefaults()
		os.Exit(1)
	}

	rawConfig, err := os.ReadFile(*configPath)
	if err != nil {
		log.Fatalf("Failed to read configuration: %v", err)
	}

	log.Printf("Starting PTunnel Desktop Agent v%s...", version)
	err = ag.Start(string(rawConfig), 0)
	if err != nil {
		log.Fatalf("Failed to start tunnel: %v", err)
	}

	log.Println("Tunnel running. Press Ctrl+C to terminate.")

	// Wait for interrupt signal
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, os.Interrupt, syscall.SIGTERM)
	<-sigChan

	log.Println("Shutting down tunnel...")
	if err := ag.Stop(); err != nil {
		log.Printf("Error stopping agent: %v", err)
	}
	log.Println("Agent stopped cleanly.")
}

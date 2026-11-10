// Package ratelimit is an in-memory token bucket keyed by an opaque string.
// Keys are hashed before use so client IPs are never held in memory in clear text.
// For more than one API instance, replace with a shared store (e.g. Redis).
package ratelimit

import (
	"crypto/sha256"
	"sync"
	"time"
)

type bucket struct {
	tokens float64
	last   time.Time
}

type Limiter struct {
	mu      sync.Mutex
	rate    float64 // tokens per second
	burst   float64
	buckets map[[32]byte]*bucket
	now     func() time.Time
}

// New allows [burst] requests at once, refilling at [perMinute] per minute.
func New(perMinute float64, burst int) *Limiter {
	l := &Limiter{rate: perMinute / 60, burst: float64(burst), buckets: map[[32]byte]*bucket{}, now: time.Now}
	go l.janitor()
	return l
}

func (l *Limiter) Allow(key string) bool {
	h := sha256.Sum256([]byte(key))
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	b, ok := l.buckets[h]
	if !ok {
		b = &bucket{tokens: l.burst, last: now}
		l.buckets[h] = b
	}
	b.tokens = min(l.burst, b.tokens+now.Sub(b.last).Seconds()*l.rate)
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func (l *Limiter) janitor() {
	for range time.Tick(10 * time.Minute) {
		l.mu.Lock()
		cutoff := l.now().Add(-time.Hour)
		for k, b := range l.buckets {
			if b.last.Before(cutoff) {
				delete(l.buckets, k)
			}
		}
		l.mu.Unlock()
	}
}

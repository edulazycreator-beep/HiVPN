//go:build nodriver

package store

// Without the pgx driver, OpenPostgres fails at runtime with "unknown driver".
const driverName = "pgx"

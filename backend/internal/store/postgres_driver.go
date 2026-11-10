//go:build !nodriver

package store

// Registers the "pgx" database/sql driver. Build with -tags nodriver to compile and
// test everything else without network access to fetch the driver.
import _ "github.com/jackc/pgx/v5/stdlib"

const driverName = "pgx"

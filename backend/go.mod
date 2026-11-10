module hivpn/backend

go 1.24

// The only third-party dependency: the PostgreSQL driver, used through database/sql.
// Run `go mod tidy` once to populate go.sum (the Dockerfile does this).
require github.com/jackc/pgx/v5 v5.7.5

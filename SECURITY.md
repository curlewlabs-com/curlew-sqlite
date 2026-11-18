# Security policy

Report suspected vulnerabilities privately using
[GitHub private vulnerability reporting](https://github.com/curlewlabs-com/curlew-sqlite/security/advisories/new).
Include the affected release or commit, the platform and OS version, the
reproduction steps, and the observed impact.

Curlew Labs maintains this repository; Jeffrey Wall
([jeffwall-curlewlabs](https://github.com/jeffwall-curlewlabs)) is the release
and security contact through GitHub. Reports are reviewed on a best-effort
basis without a guaranteed response time. Fixes target the latest published
release.

A vulnerability in SQLite itself belongs with
[the SQLite project](https://www.sqlite.org/support.html). Here it is fixed by
moving the pin to the release that carries SQLite's fix.

## Trust boundary

The build trusts sqlite.org for the two archives it pins, and nothing else: each
download is refused unless its SHA-256 is the pinned one, so a changed or
substituted archive fails the build rather than reaching the compiler or the
package. A local copy handed in for an offline build is held to the same check.

The bindings are a thin layer over SQLite's C API, and they keep its contract.
Handles are raw pointers. Passing a closed or finalized handle back is a
use-after-free, not an error, on both platforms. SQL text is executed as given,
so anything built from untrusted input must be bound as a parameter, never
interpolated. The identity check says which engine is running; it does not make
a database file from an untrusted source safe to open.

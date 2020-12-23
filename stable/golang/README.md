# Golang runtime with go mod dependency management

Implement go runtime using go mod for dependencies.

## ☕ Usage

When used, `go.mod` must declare the module kubeless. Example:

```
module function

go 1.14

require (
	...
)
```
## Braking change notice

This breaks compatibility with Go functions that use dep as package manager.

To use legacy version, replace the config map with the definition in `golang_legacy.jsonnet`

## TODO

- [ ] Release official docker images and update `golang.jsonnet`

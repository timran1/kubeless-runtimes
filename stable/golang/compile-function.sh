#!/bin/bash
GO_VERSION="1.14"

set -e

# Copy function
if [[ -e "/kubeless/go.mod" && ! -s "/kubeless/go.mod" ]]; then
  # Remove empty go.mod
  rm /kubeless/go.mod
fi
cp -r /kubeless/* /server/function/

# Replace FUNCTION placeholder
sed "s/<<FUNCTION>>/${KUBELESS_FUNC_NAME}/g" /server/kubeless.go.tpl > /server/kubeless.go
# Build command
cd /server

# Build the function and redirect stdout & stderr from the compilation step to the k8s output log
GOOS=linux GOARCH=amd64 go build -o $KUBELESS_INSTALL_VOLUME/server . > /dev/termination-log 2>&1

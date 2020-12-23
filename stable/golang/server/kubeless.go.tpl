/*
Copyright (c) 2016-2017 Bitnami

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package main

import (
	"context"
	"io/ioutil"
	"net/http"
	"os"

	"kubeless"

	proxyUtils "github.com/kubeless/kubeless/pkg/function-proxy/utils"
	"github.com/kubeless/kubeless/pkg/functions"
)

var (
	funcContext functions.Context
)

func init() {
	timeout := os.Getenv("FUNC_TIMEOUT")
	if timeout == "" {
		timeout = "180"
	}
	funcContext = functions.Context{
		FunctionName: os.Getenv("FUNC_HANDLER"),
		Timeout:      timeout,
		Runtime:      os.Getenv("FUNC_RUNTIME"),
		MemoryLimit:  os.Getenv("FUNC_MEMORY_LIMIT"),
	}
}

func health(w http.ResponseWriter, r *http.Request) {
	w.Write([]byte("OK"))
}

func handle(ctx context.Context, w http.ResponseWriter, r *http.Request) ([]byte, error) {
	data, err := ioutil.ReadAll(r.Body)
	if err != nil {
		return []byte{}, err
	}
	event := functions.Event{
		Data:           string(data),
		EventID:        r.Header.Get("event-id"),
		EventType:      r.Header.Get("event-type"),
		EventTime:      r.Header.Get("event-time"),
		EventNamespace: r.Header.Get("event-namespace"),
		Extensions: functions.Extension{
			Request:  r,
			Response: w,
			Context:  ctx,
		},
	}
	res, err := kubeless.<<FUNCTION>>(event, funcContext)
	return []byte(res), err
}

func handler(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Access-Control-Allow-Origin", "*")
	if r.Method == "OPTIONS" {
  	w.Header().Set("Access-Control-Allow-Methods", r.Header.Get("access-control-request-method"))
  	w.Header().Set("Access-Control-Allow-Headers", r.Header.Get("access-control-request-headers"))
  	return
	}
	proxyUtils.Handler(w, r, handle)
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("/", handler)
	mux.HandleFunc("/healthz", health)
	mux.Handle("/metrics", proxyUtils.PromHTTPHandler())
	server := proxyUtils.NewServer(mux)

	go func() {
		if err := server.ListenAndServe(); err != http.ErrServerClosed {
			panic(err)
		}
	}()

	proxyUtils.GracefulShutdown(server)
}

package main

import (
	"io"
	"log"
	"net/http"
	"os"
)

func main() {
	port := os.Getenv("PORT")
	if port == "" {
		port = "8080"
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain")
		io.WriteString(w, "ok")
	})
	log.Printf("go-net-http listening on :%s", port)
	if err := http.ListenAndServe("0.0.0.0:"+port, mux); err != nil {
		log.Fatal(err)
	}
}

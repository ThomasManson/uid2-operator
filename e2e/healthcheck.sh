#!/usr/bin/env bash

healthcheck() {
  attempt_counter=0
  max_attempts=5
  if [ -n "$2" ]; then
    max_attempts=$2
  fi
  echo "Healthcheck $1 for $max_attempts times"

  until (curl --output /dev/null --silent --fail "$1"); do
      if [ $attempt_counter -eq $max_attempts ];then
        echo "Max attempts reached"
        exit 1
      fi

      printf '.'
      attempt_counter=$((attempt_counter+1))
      sleep 5
      docker compose logs
  done
  echo "Healthcheck $1 succeed."
}

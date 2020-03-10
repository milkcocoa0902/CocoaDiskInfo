FROM ubuntu:latest

RUN apt update && apt install -y clang cmake git libgtkmm-3.0-dev libatasmart-dev libboost1.65-all-dev ninja-build pkg-config && apt clean && rm -rf /var/lib/apt/lists/*

COPY ./ /tmp/
CMD cd /tmp && mkdir -p build && cd $_ && cmake .. -G Ninja && ninja

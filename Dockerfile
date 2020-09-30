FROM ubuntu:latest
RUN apt-get update && \
 apt-get install -y clang\
 clang-format\
 cmake\
 git\
 ninja-build\
 libatasmart-dev\
 libboost1.65-all-dev\
 libgtkmm-3.0-dev\
 pkg-config &&\
 apt-get clean


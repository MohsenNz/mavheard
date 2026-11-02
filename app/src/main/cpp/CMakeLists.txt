cmake_minimum_required(VERSION 3.22.1)
project(h12serial C)
add_library(h12serial SHARED serial.c)
target_link_libraries(h12serial log)

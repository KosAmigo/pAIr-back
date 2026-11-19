#!/bin/sh
# 샌드박스 이미지(pair-sandbox)를 만든다. 인터넷이 필요하다(JUnit jar 내려받기).
cd "$(dirname "$0")" && docker build -t "${1:-pair-sandbox}" .

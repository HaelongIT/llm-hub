# analysis-nori는 공식 ES 이미지에 포함되어 있지 않다. 직접 구워 넣는다.
# 플러그인 버전은 ES 버전에 고정되므로 둘을 항상 함께 올린다.
#
# 폐쇄망 고객 설치 시에는 artifacts.elastic.co에서 analysis-nori-<version>.zip을
# 미리 받아 파일 경로로 설치한다 (S26/S27).
FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.3

RUN bin/elasticsearch-plugin install --batch analysis-nori

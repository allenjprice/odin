#!/usr/bin/env bash
mkdir resources/public/assets/css
echo "Installing NPM dependencies..."
npm install
echo "Compiling SASS..."
sass -E "UTF-8" style/sass/main.sass:resources/public/assets/css/styles.css --style compressed
sass -E "UTF-8" style/sass/ptm.scss:resources/public/assets/css/ptm.css --style compressed
echo "Compiling LESS..."
lessc --clean-css style/less/antd.less resources/public/assets/css/antd.css
lessc --clean-css style/less/antd-ptm.less resources/public/assets/css/antd-ptm.css
echo "Compiling Clojure & ClojureScript..."
lein uberjar

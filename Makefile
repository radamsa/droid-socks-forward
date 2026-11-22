# Android SDK и Gradle wrapper
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
GRADLE := ./gradlew

VERSION := $(shell grep -o 'versionName = "[^"]*"' app/build.gradle.kts | cut -d'"' -f2)
DIST := dist

.DEFAULT_GOAL := help

.PHONY: help setup build release clean

help:
	@echo "Цели:"
	@echo "  setup   — настройка проекта: проверка Android SDK, local.properties, wrapper"
	@echo "  build   — сборка debug APK"
	@echo "  release — сборка release APK и подготовка его в $(DIST)/"
	@echo "  clean   — очистка результатов сборки"

setup:
	@if [ -z "$(ANDROID_HOME)" ] || [ ! -d "$(ANDROID_HOME)" ]; then \
		echo "Ошибка: Android SDK не найден. Установите Android Studio или задайте ANDROID_HOME." >&2; \
		exit 1; \
	fi
	@echo "Android SDK: $(ANDROID_HOME)"
	@if [ ! -f local.properties ]; then \
		echo "sdk.dir=$(ANDROID_HOME)" > local.properties; \
		echo "Создан local.properties (sdk.dir=$(ANDROID_HOME))"; \
	else \
		echo "local.properties уже существует"; \
	fi
	@chmod +x gradlew
	@$(GRADLE) --version > /dev/null
	@echo "Готово к сборке."

build:
	$(GRADLE) :app:assembleDebug :core:test
	@echo "APK: app/build/outputs/apk/debug/app-debug.apk"

release:
	$(GRADLE) :app:assembleRelease :core:test
	@mkdir -p $(DIST)
	@if [ -f app/build/outputs/apk/release/app-release.apk ]; then \
		cp app/build/outputs/apk/release/app-release.apk $(DIST)/socks-forward-$(VERSION).apk; \
		echo "APK: $(DIST)/socks-forward-$(VERSION).apk"; \
	else \
		cp app/build/outputs/apk/release/app-release-unsigned.apk $(DIST)/socks-forward-$(VERSION)-unsigned.apk; \
		echo "APK: $(DIST)/socks-forward-$(VERSION)-unsigned.apk (без подписи)"; \
	fi

clean:
	$(GRADLE) clean
	@rm -rf $(DIST)

#!/bin/bash
# Phase 2 deployment script for Mobixy backend

echo "=== Deploying Phase 2 backend changes ==="

# Stop PM2 processes
cd /opt/mobixy/backend
pm2 stop mobixy-backend || true
pm2 delete mobixy-backend || true

# Install any new dependencies
npm install

# Restart backend
pm2 start ecosystem.config.js --name mobixy-backend

# Show status
pm2 status
pm2 logs mobixy-backend --lines 20

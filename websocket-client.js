#!/usr/bin/env node

/**
 * WebSocket Console Client for Stock Trading Platform
 * 
 * Usage: node websocket-client.js [host] [port]
 * Example: node websocket-client.js localhost 8080
 */

const WebSocket = require('ws');
const SockJS = require('sockjs-client');
const Stomp = require('stompjs');

const host = process.argv[2] || 'localhost';
const port = process.argv[3] || '8080';
const url = `http://${host}:${port}/ws/prices`;

console.log('\n📈 Stock Trading WebSocket Client');
console.log('=================================\n');
console.log(`Connecting to: ${url}\n`);

// Create SockJS connection
const socket = new SockJS(url);
const stompClient = Stomp.over(socket);

// Disable debug output
stompClient.debug = null;

stompClient.connect({}, 
    function(frame) {
        console.log('✅ Connected to WebSocket server\n');
        console.log('📊 Listening for price updates...\n');
        console.log('─'.repeat(80));
        
        stompClient.subscribe('/topic/prices', function(message) {
            const priceUpdate = JSON.parse(message.body);
            displayPriceUpdate(priceUpdate);
        });
    },
    function(error) {
        console.error('❌ Connection error:', error);
        console.log('\nMake sure the server is running and try again.');
        process.exit(1);
    }
);

function displayPriceUpdate(update) {
    const timestamp = new Date(update.timestamp).toLocaleTimeString();
    const changeSymbol = update.changePercent > 0 ? '📈' : update.changePercent < 0 ? '📉' : '➡️';
    const changeColor = update.changePercent > 0 ? '\x1b[32m' : update.changePercent < 0 ? '\x1b[31m' : '\x1b[33m';
    const resetColor = '\x1b[0m';
    
    console.log(`\n[${timestamp}] ${changeSymbol} ${update.symbol} - ${update.name}`);
    console.log(`  Price: $${update.oldPrice.toFixed(2)} → $${update.newPrice.toFixed(2)}`);
    console.log(`  Change: ${changeColor}${update.changePercent > 0 ? '+' : ''}${update.changePercent.toFixed(2)}%${resetColor}`);
    
    if (update.recentTrades && update.recentTrades.length > 0) {
        console.log(`  Recent Trades:`);
        update.recentTrades.slice(0, 3).forEach(trade => {
            const tradeTime = new Date(trade.tradeTime).toLocaleTimeString();
            console.log(`    • ${trade.quantity} shares @ $${trade.price.toFixed(2)} (${tradeTime})`);
        });
    } else {
        console.log(`  Recent Trades: None`);
    }
    
    console.log('─'.repeat(80));
}

// Handle process termination
process.on('SIGINT', function() {
    console.log('\n\nDisconnecting...');
    stompClient.disconnect(function() {
        console.log('Disconnected. Goodbye! 👋\n');
        process.exit(0);
    });
});

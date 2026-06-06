const chatWindow = document.getElementById('chatWindow');
const senderInput = document.getElementById('sender');
const messageInput = document.getElementById('message');
const sendButton = document.getElementById('sendMessage');
const executeButton = document.getElementById('executeTrade');
const coinSelect = document.getElementById('coin');
const actionSelect = document.getElementById('action');
const amountInput = document.getElementById('amount');
const tradeResult = document.getElementById('tradeResult');
const walletSummary = document.getElementById('walletSummary');
const priceList = document.getElementById('priceList');
const connectButton = document.getElementById('connectWallet');
const phantomStatus = document.getElementById('phantomStatus');
const phantomAddress = document.getElementById('phantomAddress');
const authUsername = document.getElementById('authUsername');
const authPassword = document.getElementById('authPassword');
const signupButton = document.getElementById('signupButton');
const loginButton = document.getElementById('loginButton');
const phantomSignInButton = document.getElementById('phantomSignIn');
const authMessage = document.getElementById('authMessage');
const exchangeName = document.getElementById('exchangeName');
const exchangeKey = document.getElementById('exchangeKey');
const exchangeSecret = document.getElementById('exchangeSecret');
const connectExchangeButton = document.getElementById('connectExchange');
const exchangeStatus = document.getElementById('exchangeStatus');
const exchangeBalances = document.getElementById('exchangeBalances');

let connectedPhantomAddress = null;
let sessionUser = null;

async function loadAll() {
  await Promise.all([loadChat(), loadWallet(), loadPrices(), loadSession(), checkPhantom()]);
}

async function loadChat() {
  const response = await fetch('/api/chat');
  const data = await response.json();
  chatWindow.innerHTML = data.messages
    .map(msg => `<div class="chat-message"><span class="chat-meta">[${msg.timestamp}] ${msg.sender}:</span> ${escapeHtml(msg.text)}</div>`)
    .join('');
  chatWindow.scrollTop = chatWindow.scrollHeight;
}

async function loadWallet() {
  const response = await fetch('/api/wallet');
  if (response.status === 401) {
    walletSummary.innerHTML = '<div class="wallet-row"><strong>Sign in</strong> to view wallet details.</div>';
    return;
  }
  const data = await response.json();
  walletSummary.innerHTML = `
    <div class="wallet-row"><strong>USD Balance:</strong> $${data.usdBalance.toFixed(2)}</div>
    ${Object.entries(data.holdings)
      .map(([coin, value]) => `<div class="wallet-row"><strong>${coin.toUpperCase()}:</strong> ${value.toFixed(4)}</div>`)
      .join('')}
  `;
}

async function loadPrices() {
  const response = await fetch('/api/prices');
  const data = await response.json();
  priceList.innerHTML = Object.entries(data.prices)
    .map(([coin, value]) => `<div class="price-row"><strong>${coin.toUpperCase()}:</strong> $${Number(value).toFixed(4)}</div>`)
    .join('');
}

async function loadSession() {
  const response = await fetch('/api/session');
  const data = await response.json();
  sessionUser = data.user && data.user.username ? data.user : null;
  authMessage.textContent = sessionUser ? `Signed in as ${sessionUser.username}` : 'Not signed in.';
  if (sessionUser && sessionUser.exchangeAccounts) {
    exchangeStatus.textContent = 'Connected exchanges: ' + Object.keys(sessionUser.exchangeAccounts).join(', ');
    if (sessionUser.exchangeAccounts.binance) {
      await loadExchangeBalances();
    }
  } else {
    exchangeStatus.textContent = 'No exchange connected.';
    exchangeBalances.innerHTML = '';
  }
}

async function loadExchangeBalances() {
  const response = await fetch('/api/exchange/balance');
  if (!response.ok) {
    exchangeBalances.innerHTML = `<div class="wallet-row"><strong>Exchange balances:</strong> unable to load.</div>`;
    return;
  }
  const data = await response.json();
  const balances = data.balances || [];
  if (!balances.length) {
    exchangeBalances.innerHTML = `<div class="wallet-row"><strong>Exchange balances:</strong> no assets with non-zero balance.</div>`;
    return;
  }
  exchangeBalances.innerHTML = balances
    .map(balance => `<div class="wallet-row"><strong>${balance.asset}:</strong> free ${Number(balance.free).toFixed(4)}, locked ${Number(balance.locked).toFixed(4)}</div>`)
    .join('');
}

async function signup() {
  const username = authUsername.value.trim();
  const password = authPassword.value.trim();
  if (!username || !password) {
    authMessage.textContent = 'Username and password are required.';
    return;
  }

  const response = await fetch('/api/auth/signup', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const result = await response.json();
  authMessage.textContent = response.ok ? 'Sign up successful.' : result.error || 'Sign up failed.';
  await loadAll();
}

async function login() {
  const username = authUsername.value.trim();
  const password = authPassword.value.trim();
  if (!username || !password) {
    authMessage.textContent = 'Username and password are required.';
    return;
  }

  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const result = await response.json();
  authMessage.textContent = response.ok ? 'Sign in successful.' : result.error || 'Sign in failed.';
  await loadAll();
}

async function phantomSignIn() {
  if (typeof window.solana === 'undefined' || !window.solana.isPhantom) {
    authMessage.textContent = 'Phantom wallet not found.';
    return;
  }
  try {
    const resp = await window.solana.connect();
    connectedPhantomAddress = resp.publicKey.toString();
    await fetch('/api/auth/phantom', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ publicKey: connectedPhantomAddress }),
    });
    authMessage.textContent = 'Signed in with Phantom.';
    await loadAll();
  } catch (error) {
    authMessage.textContent = 'Phantom sign in failed.';
  }
}

async function connectExchange() {
  if (!sessionUser) {
    exchangeStatus.textContent = 'Sign in first before connecting an exchange.';
    return;
  }
  const exchange = exchangeName.value.trim();
  const apiKey = exchangeKey.value.trim();
  const apiSecret = exchangeSecret.value.trim();
  if (!exchange || !apiKey || !apiSecret) {
    exchangeStatus.textContent = 'Exchange name, API key, and secret are required.';
    return;
  }

  const response = await fetch('/api/exchange/connect', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ exchange, apiKey, apiSecret }),
  });
  const result = await response.json();
  exchangeStatus.textContent = response.ok ? result.message : result.error || 'Exchange connect failed.';
  await loadAll();
}

async function checkPhantom() {
  if (typeof window.solana === 'undefined' || !window.solana.isPhantom) {
    phantomStatus.textContent = 'Phantom wallet not found. Install Phantom browser extension.';
    connectButton.disabled = true;
    phantomAddress.textContent = '';
    connectButton.textContent = 'Connect Phantom';
    return;
  }

  connectButton.disabled = false;
  connectButton.textContent = connectedPhantomAddress ? 'Disconnect Phantom' : 'Connect Phantom';
  phantomStatus.textContent = connectedPhantomAddress ? 'Connected with Phantom' : 'Phantom available but not connected';
  phantomAddress.innerHTML = connectedPhantomAddress
    ? `<strong>Address:</strong> ${truncateAddress(connectedPhantomAddress)}`
    : '';
}

async function connectPhantom() {
  if (typeof window.solana === 'undefined' || !window.solana.isPhantom) {
    phantomStatus.textContent = 'Phantom wallet is not available in this browser.';
    return;
  }

  try {
    if (connectedPhantomAddress) {
      if (window.solana.disconnect) {
        await window.solana.disconnect();
      }
      connectedPhantomAddress = null;
    } else {
      const response = await window.solana.connect();
      connectedPhantomAddress = response.publicKey.toString();
    }
  } catch (error) {
    console.error('Phantom connection failed', error);
    phantomStatus.textContent = 'Unable to connect with Phantom.';
  }

  await checkPhantom();
}

function truncateAddress(address) {
  return `${address.slice(0, 6)}...${address.slice(-6)}`;
}

async function sendChat() {
  const sender = senderInput.value.trim() || (connectedPhantomAddress ? truncateAddress(connectedPhantomAddress) : 'Trader');
  const text = messageInput.value.trim();
  if (!text) return;
  await fetch('/api/chat', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sender, text }),
  });
  messageInput.value = '';
  await loadChat();
}

async function executeTrade() {
  const action = actionSelect.value;
  const coin = coinSelect.value;
  const amount = Number(amountInput.value);
  if (amount <= 0) {
    tradeResult.textContent = 'Enter a valid amount to execute a deal.';
    return;
  }
  const response = await fetch('/api/trade', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ action, coin, amount }),
  });
  const result = await response.json();
  if (response.ok) {
    tradeResult.textContent = result.message;
    loadAll();
  } else if (response.status === 401) {
    tradeResult.textContent = 'Please sign in before executing trades.';
  } else {
    tradeResult.textContent = result.error || 'Trade failed.';
  }
}

function escapeHtml(text) {
  return text.replace(/[&<>"]+/g, match => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[match]));
}

sendButton.addEventListener('click', sendChat);
messageInput.addEventListener('keydown', event => {
  if (event.key === 'Enter') {
    sendChat();
  }
});
connectButton.addEventListener('click', connectPhantom);
signupButton.addEventListener('click', signup);
loginButton.addEventListener('click', login);
phantomSignInButton.addEventListener('click', phantomSignIn);
connectExchangeButton.addEventListener('click', connectExchange);
executeButton.addEventListener('click', executeTrade);

loadAll();
setInterval(loadAll, 5000);

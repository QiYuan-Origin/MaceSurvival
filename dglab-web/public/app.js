let socket;
let reconnectTimer = null;

const els = {
  statusPill: document.querySelector('#statusPill'),
  mcPill: document.querySelector('#mcPill'),
  connectDglab: document.querySelector('#connectDglab'),
  qrCode: document.querySelector('#qrCode'),
  qrPlaceholder: document.querySelector('#qrPlaceholder'),
  appUrl: document.querySelector('#appUrl'),
  copyAppUrl: document.querySelector('#copyAppUrl'),
  mcUrl: document.querySelector('#mcUrl'),
  copyMcUrl: document.querySelector('#copyMcUrl'),
  bridgeEnabled: document.querySelector('#bridgeEnabled'),
  deviceSelect: document.querySelector('#deviceSelect'),
  deviceInfo: document.querySelector('#deviceInfo'),
  limitA: document.querySelector('#limitA'),
  limitB: document.querySelector('#limitB'),
  limitAValue: document.querySelector('#limitAValue'),
  limitBValue: document.querySelector('#limitBValue'),
  saveLimits: document.querySelector('#saveLimits'),
  intensityA: document.querySelector('#intensityA'),
  intensityB: document.querySelector('#intensityB'),
  intensityAValue: document.querySelector('#intensityAValue'),
  intensityBValue: document.querySelector('#intensityBValue'),
  stopAll: document.querySelector('#stopAll'),
  refreshDevices: document.querySelector('#refreshDevices'),
  log: document.querySelector('#log'),
};

let currentState = null;
let setIntensityTimer = null;

function send(message) {
  if (socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(message));
  }
}

function connectControlSocket() {
  socket = new WebSocket(`ws://${location.host}/control`);

  socket.addEventListener('open', () => {
    els.log.textContent = '本地控制页已连接。';
  });

  socket.addEventListener('message', (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'state') {
      render(message);
    } else if (message.type === 'error') {
      els.log.textContent = message.message;
    }
  });

  socket.addEventListener('close', () => {
    els.statusPill.textContent = '本地服务断开';
    clearTimeout(reconnectTimer);
    reconnectTimer = setTimeout(connectControlSocket, 1000);
  });
}

function stateName(value) {
  const text = String(value || 'idle');
  if (text.includes('Paired') || text === 'paired') return '已配对';
  if (text.includes('Waiting') || text.includes('waiting')) return '等待 App';
  if (text.includes('Connecting') || text.includes('connecting')) return '连接中';
  if (text.includes('Disconnected') || text === 'disconnected') return '已断开';
  return '未连接';
}

function copy(value) {
  navigator.clipboard?.writeText(value).catch(() => {});
}

function formatDevice(device) {
  if (!device) {
    return '还没有设备接入';
  }
  const props = device.props || {};
  const state = device.slotState || {};
  const power = Number.isFinite(props.power) ? `${props.power}%` : '未知';
  const a = Number.isFinite(props.intensityA) ? props.intensityA : 0;
  const b = Number.isFinite(props.intensityB) ? props.intensityB : 0;
  return [
    `名称：${device.name || device.type || 'DG-LAB 设备'}`,
    `电量：${power}`,
    `A/B：${a} / ${b}`,
    `状态：${state.hasDevice === false ? '未连接设备' : '已接入'}`,
  ].join('\n');
}

function render(state) {
  currentState = state;
  els.statusPill.textContent = stateName(state.state);
  els.mcPill.textContent = `MC ${state.buttplugClients || 0}`;
  els.mcUrl.value = state.minecraftSocket || `ws://${location.host}`;
  els.appUrl.value = state.appSocketUrl || '';
  els.bridgeEnabled.checked = Boolean(state.bridgeEnabled);
  els.limitA.value = state.maxIntensity?.A ?? 20;
  els.limitB.value = state.maxIntensity?.B ?? 20;
  els.limitAValue.value = els.limitA.value;
  els.limitBValue.value = els.limitB.value;

  if (state.qrCode) {
    els.qrCode.src = state.qrCode;
    els.qrCode.style.display = 'block';
    els.qrPlaceholder.style.display = 'none';
  } else {
    els.qrCode.removeAttribute('src');
    els.qrCode.style.display = 'none';
    els.qrPlaceholder.style.display = 'block';
  }

  const devices = state.devices || [];
  const selected = state.slotId;
  els.deviceSelect.innerHTML = '';
  if (devices.length === 0) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = '没有设备';
    els.deviceSelect.append(option);
  } else {
    for (const device of devices) {
      const option = document.createElement('option');
      option.value = device.slotId;
      option.textContent = `${device.name || device.type || 'DG-LAB'} (${device.slotId})`;
      option.selected = device.slotId === selected;
      els.deviceSelect.append(option);
    }
  }

  const device = devices.find((entry) => entry.slotId === selected) || devices[0];
  els.deviceInfo.textContent = formatDevice(device);

  const log = {
    state: stateName(state.state),
    targetId: state.targetId,
    clientId: state.clientId,
    slotId: state.slotId,
    maxIntensity: state.maxIntensity,
    minecraftSocket: state.minecraftSocket,
    lastError: state.lastError,
  };
  els.log.textContent = JSON.stringify(log, null, 2);
}

els.connectDglab.addEventListener('click', () => {
  send({ type: 'connect-dglab' });
});

els.copyAppUrl.addEventListener('click', () => {
  copy(els.appUrl.value);
});

els.copyMcUrl.addEventListener('click', () => {
  copy(els.mcUrl.value);
});

els.bridgeEnabled.addEventListener('change', () => {
  send({ type: 'set-bridge', enabled: els.bridgeEnabled.checked });
});

els.deviceSelect.addEventListener('change', () => {
  send({ type: 'select-slot', slotId: els.deviceSelect.value });
});

for (const [slider, output] of [
  [els.limitA, els.limitAValue],
  [els.limitB, els.limitBValue],
  [els.intensityA, els.intensityAValue],
  [els.intensityB, els.intensityBValue],
]) {
  slider.addEventListener('input', () => {
    output.value = slider.value;
  });
}

els.saveLimits.addEventListener('click', () => {
  send({ type: 'set-limits', A: Number(els.limitA.value), B: Number(els.limitB.value) });
});

function scheduleSetIntensity(channel, value) {
  clearTimeout(setIntensityTimer);
  setIntensityTimer = setTimeout(() => {
    send({ type: 'set-intensity', channel, value: Number(value) });
  }, 160);
}

els.intensityA.addEventListener('input', () => {
  scheduleSetIntensity('A', els.intensityA.value);
});

els.intensityB.addEventListener('input', () => {
  scheduleSetIntensity('B', els.intensityB.value);
});

for (const button of document.querySelectorAll('[data-pulse]')) {
  button.addEventListener('click', () => {
    const channel = button.dataset.pulse;
    send({ type: 'pulse', channel, duration: 1500 });
  });
}

els.stopAll.addEventListener('click', () => {
  els.intensityA.value = 0;
  els.intensityB.value = 0;
  els.intensityAValue.value = 0;
  els.intensityBValue.value = 0;
  send({ type: 'stop' });
});

els.refreshDevices.addEventListener('click', () => {
  send({ type: 'refresh-devices' });
});

window.addEventListener('beforeunload', () => {
  if (currentState?.desiredIntensity?.A || currentState?.desiredIntensity?.B) {
    send({ type: 'stop' });
  }
});

connectControlSocket();

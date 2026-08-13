import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import QRCode from 'qrcode';
import {
  COYOTE_WAVEFORM,
  COYOTE_WAVEFORMS,
  DglabSocket,
  V4Channel,
} from 'dglab-kit';
import { WebSocketServer } from 'ws';

const HOST = process.env.DGLAB_HOST || '127.0.0.1';
const PORT = Number.parseInt(process.env.DGLAB_PORT || '12345', 10);
const DGLAB_URL = process.env.DGLAB_URL || 'wss://trex.dungeon-lab.cn/v4';
const PUBLIC_DIR = join(fileURLToPath(new URL('.', import.meta.url)), 'public');
const WATCHDOG_MS = 8000;
const PULSE_DURATION_MS = 3000;
const PULSE_IDLE_MS = 80;
const HARD_LIMIT = 200;

const mimeTypes = new Map([
  ['.html', 'text/html; charset=utf-8'],
  ['.css', 'text/css; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.png', 'image/png'],
  ['.svg', 'image/svg+xml'],
]);

let dglab = null;
let state = 'idle';
let targetId = null;
let appSocketUrl = null;
let qrCode = null;
let clientId = null;
let slotId = null;
let devices = [];
let lastError = null;
let maxIntensity = { A: 20, B: 20 };
let desiredIntensity = { A: 0, B: 0 };
let knownIntensity = { A: 0, B: 0 };
let bridgeEnabled = true;
let waveformKey = COYOTE_WAVEFORM.BUBBLE;
let pulseLoops = { A: null, B: null };
let watchdogTimer = null;

const webClients = new Set();
const buttplugClients = new Set();

function clamp(value, min, max) {
  if (!Number.isFinite(value)) {
    return min;
  }
  return Math.min(Math.max(value, min), max);
}

function toChannelName(channel) {
  return channel === V4Channel.B || channel === 1 || channel === 'B' ? 'B' : 'A';
}

function toV4Channel(channelName) {
  return channelName === 'B' ? V4Channel.B : V4Channel.A;
}

function getCurrentIntensity(channelName) {
  const device = devices.find((entry) => entry.slotId === slotId);
  const propKey = channelName === 'A' ? 'intensityA' : 'intensityB';
  const value = device?.props?.[propKey];
  return Number.isFinite(value) ? value : knownIntensity[channelName];
}

function mergeDevice(base, patch) {
  return {
    ...base,
    ...patch,
    props: {
      ...(base?.props || {}),
      ...(patch?.props || {}),
    },
    slotState: {
      ...(base?.slotState || {}),
      ...(patch?.slotState || {}),
    },
  };
}

function updateDevices(nextDevices, nextClientId) {
  if (nextClientId && nextClientId !== clientId) {
    return;
  }
  devices = nextDevices;
  if (!slotId || !devices.some((device) => device.slotId === slotId)) {
    slotId = devices[0]?.slotId || null;
  }
  for (const device of devices) {
    if (Number.isFinite(device?.props?.intensityA)) {
      knownIntensity.A = device.props.intensityA;
    }
    if (Number.isFinite(device?.props?.intensityB)) {
      knownIntensity.B = device.props.intensityB;
    }
  }
  broadcastState();
  if (isDglabReady()) {
    notifyButtplugDeviceAdded();
  }
}

function getStatePayload() {
  return {
    type: 'state',
    state,
    dglabUrl: DGLAB_URL,
    targetId,
    appSocketUrl,
    qrCode,
    clientId,
    slotId,
    devices,
    maxIntensity,
    desiredIntensity,
    knownIntensity,
    bridgeEnabled,
    waveformKey,
    minecraftSocket: `ws://${HOST}:${PORT}`,
    webUrl: `http://${HOST}:${PORT}/`,
    lastError,
    buttplugClients: buttplugClients.size,
  };
}

function sendJson(socket, payload) {
  if (socket.readyState === socket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}

function broadcastState() {
  const payload = getStatePayload();
  for (const socket of webClients) {
    sendJson(socket, payload);
  }
}

function setState(nextState) {
  state = nextState;
  broadcastState();
}

function setError(error) {
  lastError = error instanceof Error ? error.message : String(error);
  broadcastState();
}

function isDglabReady() {
  return Boolean(dglab && clientId && slotId);
}

async function connectDglab() {
  if (dglab && state !== 'disconnected' && state !== 'idle') {
    return getStatePayload();
  }

  await disconnectDglab(false);
  setState('connecting');

  dglab = new DglabSocket({
    url: DGLAB_URL,
    connectTimeout: 10000,
    responseTimeout: 5000,
  });

  dglab.on('state', (nextState) => {
    state = String(nextState);
    broadcastState();
  });

  dglab.on('client-attached', async (nextClientId) => {
    clientId = nextClientId;
    setState('paired');
    try {
      const response = await dglab.requestDevices(clientId);
      updateDevices(response.devices || [], clientId);
    } catch (error) {
      setError(error);
    }
  });

  dglab.on('client-disconnected', (nextClientId) => {
    if (nextClientId === clientId) {
      notifyButtplugDeviceRemoved();
      clientId = null;
      slotId = null;
      devices = [];
      knownIntensity = { A: 0, B: 0 };
      desiredIntensity = { A: 0, B: 0 };
      stopPulseLoop('A');
      stopPulseLoop('B');
      setState('waiting-for-peer');
    }
  });

  dglab.on('devices', (nextDevices, nextClientId) => {
    updateDevices(nextDevices || [], nextClientId);
  });

  dglab.on('device', (device, nextClientId) => {
    if (!device || (nextClientId && nextClientId !== clientId)) {
      return;
    }
    const index = devices.findIndex((entry) => entry.slotId === device.slotId);
    if (index >= 0) {
      devices[index] = mergeDevice(devices[index], device);
    } else {
      devices.push(device);
    }
    updateDevices(devices, nextClientId);
  });

  dglab.on('error', (error) => {
    setError(error);
  });

  dglab.on('close', () => {
    setState('disconnected');
  });

  const response = await dglab.connect();
  targetId = response.targetId;
  appSocketUrl = `${DGLAB_URL}/?tid=${encodeURIComponent(targetId)}`;
  const scanUrl = `https://dungeon-lab.cn/s/?v=1&action=socket&url=${encodeURIComponent(appSocketUrl)}`;
  qrCode = await QRCode.toDataURL(scanUrl, { margin: 1, width: 280 });
  setState('waiting-for-peer');
  return getStatePayload();
}

async function disconnectDglab(notify = true) {
  stopPulseLoop('A');
  stopPulseLoop('B');
  if (watchdogTimer) {
    clearTimeout(watchdogTimer);
    watchdogTimer = null;
  }
  if (dglab) {
    try {
      await stopAllOutput(false);
    } catch {
      // Disconnect should continue even if the remote side is already gone.
    }
    try {
      dglab.close?.();
      dglab.disconnect?.();
    } catch {
      // Some SDK versions expose only websocket lifecycle events.
    }
  }
  dglab = null;
  targetId = null;
  appSocketUrl = null;
  qrCode = null;
  clientId = null;
  slotId = null;
  devices = [];
  knownIntensity = { A: 0, B: 0 };
  desiredIntensity = { A: 0, B: 0 };
  state = 'idle';
  if (notify) {
    broadcastState();
  }
}

function assertReady() {
  if (!isDglabReady()) {
    throw new Error('DG-LAB App 还没有接入，先用 App 扫码连接。');
  }
}

function getWaveformFrames() {
  return COYOTE_WAVEFORMS[waveformKey]?.raw
    || COYOTE_WAVEFORMS[COYOTE_WAVEFORM.BUBBLE]?.raw
    || ['0A0A0A0A00000000'];
}

function startPulseLoop(channelName) {
  if (pulseLoops[channelName]) {
    return;
  }
  pulseLoops[channelName] = runPulseLoop(channelName)
    .catch((error) => setError(error))
    .finally(() => {
      pulseLoops[channelName] = null;
    });
}

function stopPulseLoop(channelName) {
  desiredIntensity[channelName] = 0;
}

async function runPulseLoop(channelName) {
  while (desiredIntensity[channelName] > 0 && dglab && clientId && slotId) {
    try {
      await dglab.sendPulse(
        clientId,
        slotId,
        toV4Channel(channelName),
        PULSE_DURATION_MS,
        getWaveformFrames(),
        { timeout: PULSE_DURATION_MS + 2500, priority: 1, immediate: true },
      );
    } catch (error) {
      setError(error);
      await delay(500);
    }
    await delay(PULSE_IDLE_MS);
  }
}

function delay(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function setChannelIntensity(channelName, value, { startPulse = true } = {}) {
  assertReady();
  const limit = clamp(maxIntensity[channelName], 0, HARD_LIMIT);
  const nextValue = clamp(Math.round(value), 0, limit);
  const currentValue = clamp(Math.round(getCurrentIntensity(channelName)), 0, HARD_LIMIT);
  desiredIntensity[channelName] = nextValue;

  if (nextValue === 0) {
    stopPulseLoop(channelName);
    try {
      await dglab.clearOperate(clientId, { slotId, channel: toV4Channel(channelName) });
    } catch {
      // Clearing a quiet channel can race with a completed pulse task.
    }
    await dglab.resetIntensity(clientId, slotId, toV4Channel(channelName), { timeout: 5000 });
    knownIntensity[channelName] = 0;
    broadcastState();
    return;
  }

  const delta = nextValue - currentValue;
  if (delta > 0) {
    await dglab.addIntensity(clientId, slotId, toV4Channel(channelName), delta, { timeout: 5000 });
  } else if (delta < 0) {
    await dglab.reduceStrength(clientId, slotId, toV4Channel(channelName), Math.abs(delta), { timeout: 5000 });
  }

  knownIntensity[channelName] = nextValue;
  if (startPulse) {
    startPulseLoop(channelName);
  }
  broadcastState();
}

async function stopAllOutput(notify = true) {
  desiredIntensity = { A: 0, B: 0 };
  if (dglab && clientId) {
    try {
      await dglab.clearOperate(clientId);
    } catch {
      // Ignore stale remote task errors during emergency stop.
    }
    if (slotId) {
      try {
        await dglab.resetIntensity(clientId, slotId, V4Channel.A, { timeout: 5000 });
      } catch {
        // Channel might not exist anymore.
      }
      try {
        await dglab.resetIntensity(clientId, slotId, V4Channel.B, { timeout: 5000 });
      } catch {
        // Channel might not exist anymore.
      }
    }
  }
  knownIntensity = { A: 0, B: 0 };
  if (notify) {
    broadcastState();
  }
}

function refreshWatchdog() {
  if (watchdogTimer) {
    clearTimeout(watchdogTimer);
  }
  watchdogTimer = setTimeout(() => {
    stopAllOutput().catch((error) => setError(error));
  }, WATCHDOG_MS);
}

async function handleControlMessage(socket, raw) {
  let message;
  try {
    message = JSON.parse(raw);
  } catch {
    sendJson(socket, { type: 'error', message: '控制消息不是合法 JSON。' });
    return;
  }

  try {
    switch (message.type) {
      case 'connect-dglab':
        sendJson(socket, await connectDglab());
        break;
      case 'disconnect-dglab':
        await disconnectDglab();
        break;
      case 'refresh-devices': {
        assertReady();
        const response = await dglab.requestDevices(clientId);
        updateDevices(response.devices || [], clientId);
        break;
      }
      case 'set-limits':
        maxIntensity = {
          A: clamp(Number(message.A), 0, HARD_LIMIT),
          B: clamp(Number(message.B), 0, HARD_LIMIT),
        };
        broadcastState();
        break;
      case 'select-slot':
        if (devices.some((device) => device.slotId === message.slotId)) {
          slotId = message.slotId;
          broadcastState();
        }
        break;
      case 'set-intensity':
        await setChannelIntensity(toChannelName(message.channel), Number(message.value));
        break;
      case 'temp-intensity':
        assertReady();
        await dglab.setTempIntensity(
          clientId,
          slotId,
          toV4Channel(toChannelName(message.channel)),
          clamp(Number(message.value), 0, maxIntensity[toChannelName(message.channel)]),
          clamp(Number(message.duration), 200, 10000),
          { timeout: 15000, immediate: true },
        );
        break;
      case 'pulse':
        assertReady();
        await dglab.sendPulse(
          clientId,
          slotId,
          toV4Channel(toChannelName(message.channel)),
          clamp(Number(message.duration), 300, 10000),
          getWaveformFrames(),
          { timeout: 15000, immediate: true },
        );
        break;
      case 'stop':
        await stopAllOutput();
        break;
      case 'set-bridge':
        bridgeEnabled = Boolean(message.enabled);
        if (!bridgeEnabled) {
          await stopAllOutput();
        }
        broadcastState();
        break;
      case 'set-waveform':
        if (COYOTE_WAVEFORMS[message.waveform]) {
          waveformKey = message.waveform;
          broadcastState();
        }
        break;
      default:
        sendJson(socket, { type: 'error', message: `未知控制消息: ${message.type}` });
    }
  } catch (error) {
    setError(error);
    sendJson(socket, { type: 'error', message: error.message });
  }
}

function buttplugDeviceMessages(messageVersion) {
  const scalarFeatures = [
    {
      FeatureDescriptor: 'DG-LAB A 通道',
      ActuatorType: 'Vibrate',
      StepCount: 200,
    },
    {
      FeatureDescriptor: 'DG-LAB B 通道',
      ActuatorType: 'Vibrate',
      StepCount: 200,
    },
  ];
  const deviceMessages = {
    ScalarCmd: scalarFeatures,
    StopDeviceCmd: {},
    VibrateCmd: {
      FeatureCount: 2,
    },
  };
  if (messageVersion >= 3) {
    deviceMessages.BatteryLevelCmd = {};
  }
  return deviceMessages;
}

function buttplugDevice(messageVersion) {
  return {
    DeviceName: 'DG-LAB Coyote Socket Bridge',
    DeviceIndex: 0,
    DeviceMessages: buttplugDeviceMessages(messageVersion),
  };
}

function currentButtplugDevices(messageVersion) {
  return isDglabReady() ? [buttplugDevice(messageVersion)] : [];
}

function notifyButtplugDeviceAdded() {
  for (const socket of buttplugClients) {
    const version = Number(socket.messageVersion || 3);
    sendButtplug(socket, { DeviceAdded: { Id: 0, ...buttplugDevice(version) } });
  }
}

function notifyButtplugDeviceRemoved() {
  for (const socket of buttplugClients) {
    sendButtplug(socket, {
      DeviceRemoved: {
        Id: 0,
        DeviceIndex: 0,
      },
    });
  }
}

function sendButtplug(socket, payload) {
  if (socket.readyState === socket.OPEN) {
    socket.send(JSON.stringify(Array.isArray(payload) ? payload : [payload]));
  }
}

function ok(id) {
  return { Ok: { Id: id } };
}

function errorMessage(id, message, code = 1) {
  return {
    Error: {
      Id: id,
      ErrorMessage: message,
      ErrorCode: code,
    },
  };
}

function readButtplugMessage(entry) {
  const [kind, payload] = Object.entries(entry || {})[0] || [];
  return { kind, payload: payload || {} };
}

async function handleButtplugMessage(socket, raw) {
  let frames;
  try {
    frames = JSON.parse(raw);
  } catch {
    sendButtplug(socket, errorMessage(0, 'Invalid JSON.'));
    return;
  }
  if (!Array.isArray(frames)) {
    frames = [frames];
  }

  const responses = [];
  for (const frame of frames) {
    const { kind, payload } = readButtplugMessage(frame);
    const id = Number(payload.Id) || 0;
    const version = Number(socket.messageVersion || payload.MessageVersion || 3);

    try {
      switch (kind) {
        case 'RequestServerInfo':
          socket.messageVersion = clamp(Number(payload.MessageVersion) || 3, 1, 3);
          responses.push({
            ServerInfo: {
              Id: id,
              ServerName: 'DG-LAB Coyote Socket Bridge',
              MessageVersion: socket.messageVersion,
              MaxPingTime: 0,
            },
          });
          break;
        case 'RequestDeviceList':
          responses.push({
            DeviceList: {
              Id: id,
              Devices: currentButtplugDevices(version),
            },
          });
          break;
        case 'StartScanning':
          responses.push(ok(id));
          setTimeout(() => {
            if (isDglabReady()) {
              sendButtplug(socket, { DeviceAdded: { Id: 0, ...buttplugDevice(version) } });
            }
            sendButtplug(socket, { ScanningFinished: { Id: 0 } });
          }, 100);
          break;
        case 'StopScanning':
        case 'Ping':
          responses.push(ok(id));
          break;
        case 'StopDeviceCmd':
        case 'StopAllDevices':
          await stopAllOutput();
          responses.push(ok(id));
          break;
        case 'ScalarCmd':
          await applyScalarCommand(payload);
          responses.push(ok(id));
          break;
        case 'VibrateCmd':
          await applyVibrateCommand(payload);
          responses.push(ok(id));
          break;
        case 'BatteryLevelCmd':
          responses.push({
            BatteryLevelReading: {
              Id: id,
              DeviceIndex: 0,
              BatteryLevel: readBatteryLevel(),
            },
          });
          break;
        default:
          responses.push(errorMessage(id, `Unsupported message: ${kind}`));
      }
    } catch (error) {
      setError(error);
      responses.push(errorMessage(id, error.message));
    }
  }

  sendButtplug(socket, responses);
}

function readBatteryLevel() {
  const device = devices.find((entry) => entry.slotId === slotId);
  const power = Number(device?.props?.power);
  if (!Number.isFinite(power)) {
    return 1;
  }
  return clamp(power / 100, 0, 1);
}

async function applyScalarCommand(payload) {
  if (!bridgeEnabled) {
    return;
  }
  refreshWatchdog();
  const scalars = Array.isArray(payload.Scalars) ? payload.Scalars : [];
  for (const scalar of scalars) {
    const index = Number(scalar.Index) || 0;
    const channelName = index === 1 ? 'B' : 'A';
    const normalized = clamp(Number(scalar.Scalar), 0, 1);
    const value = Math.round(normalized * maxIntensity[channelName]);
    await setChannelIntensity(channelName, value);
  }
}

async function applyVibrateCommand(payload) {
  if (!bridgeEnabled) {
    return;
  }
  refreshWatchdog();
  const speeds = Array.isArray(payload.Speeds) ? payload.Speeds : [];
  for (const speed of speeds) {
    const index = Number(speed.Index) || 0;
    const channelName = index === 1 ? 'B' : 'A';
    const normalized = clamp(Number(speed.Speed), 0, 1);
    const value = Math.round(normalized * maxIntensity[channelName]);
    await setChannelIntensity(channelName, value);
  }
}

async function serveStatic(request, response) {
  const url = new URL(request.url || '/', `http://${request.headers.host || `${HOST}:${PORT}`}`);
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/') {
    pathname = '/index.html';
  }
  const normalizedPath = normalize(pathname).replace(/^([/\\])+/, '');
  const filePath = join(PUBLIC_DIR, normalizedPath);
  if (!filePath.startsWith(PUBLIC_DIR)) {
    response.writeHead(403);
    response.end('Forbidden');
    return;
  }
  try {
    const content = await readFile(filePath);
    const extension = filePath.slice(filePath.lastIndexOf('.'));
    response.writeHead(200, {
      'content-type': mimeTypes.get(extension) || 'application/octet-stream',
      'cache-control': 'no-store',
    });
    response.end(content);
  } catch {
    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('Not Found');
  }
}

const server = createServer((request, response) => {
  if (request.url === '/api/state') {
    response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify(getStatePayload()));
    return;
  }
  serveStatic(request, response).catch((error) => {
    response.writeHead(500, { 'content-type': 'text/plain; charset=utf-8' });
    response.end(error.message);
  });
});

const webWss = new WebSocketServer({ noServer: true });
const buttplugWss = new WebSocketServer({ noServer: true });

webWss.on('connection', (socket) => {
  webClients.add(socket);
  sendJson(socket, getStatePayload());
  socket.on('message', (raw) => {
    handleControlMessage(socket, raw.toString()).catch((error) => setError(error));
  });
  socket.on('close', () => {
    webClients.delete(socket);
  });
});

buttplugWss.on('connection', (socket) => {
  buttplugClients.add(socket);
  broadcastState();
  socket.on('message', (raw) => {
    handleButtplugMessage(socket, raw.toString()).catch((error) => setError(error));
  });
  socket.on('close', () => {
    buttplugClients.delete(socket);
    broadcastState();
  });
});

server.on('upgrade', (request, socket, head) => {
  const url = new URL(request.url || '/', `http://${request.headers.host || `${HOST}:${PORT}`}`);
  if (url.pathname === '/control') {
    webWss.handleUpgrade(request, socket, head, (ws) => {
      webWss.emit('connection', ws, request);
    });
    return;
  }
  buttplugWss.handleUpgrade(request, socket, head, (ws) => {
    buttplugWss.emit('connection', ws, request);
  });
});

server.listen(PORT, HOST, () => {
  console.log(`DG-LAB web: http://${HOST}:${PORT}/`);
  console.log(`Minecraft/Buttplug socket: ws://${HOST}:${PORT}`);
});

process.on('SIGINT', async () => {
  await stopAllOutput().catch(() => {});
  process.exit(0);
});

process.on('SIGTERM', async () => {
  await stopAllOutput().catch(() => {});
  process.exit(0);
});

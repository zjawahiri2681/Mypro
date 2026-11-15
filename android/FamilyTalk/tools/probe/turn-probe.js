// Checks which free TURN/STUN servers actually hand out candidates (run in CI, which has internet).
const puppeteer = require('puppeteer-core');
const servers = [
  { name: 'google stun', cfg: { urls: 'stun:stun.l.google.com:19302' }, want: 'srflx' },
  { name: 'cloudflare stun', cfg: { urls: 'stun:stun.cloudflare.com:3478' }, want: 'srflx' },
  { name: 'openrelay udp 80', cfg: { urls: 'turn:openrelay.metered.ca:80', username: 'openrelayproject', credential: 'openrelayproject' }, want: 'relay' },
  { name: 'openrelay udp 443', cfg: { urls: 'turn:openrelay.metered.ca:443', username: 'openrelayproject', credential: 'openrelayproject' }, want: 'relay' },
  { name: 'openrelay tcp 443', cfg: { urls: 'turn:openrelay.metered.ca:443?transport=tcp', username: 'openrelayproject', credential: 'openrelayproject' }, want: 'relay' },
  { name: 'openrelay tls 443', cfg: { urls: 'turns:openrelay.metered.ca:443?transport=tcp', username: 'openrelayproject', credential: 'openrelayproject' }, want: 'relay' },
  { name: 'freestun udp 3478', cfg: { urls: 'turn:freestun.net:3478', username: 'free', credential: 'free' }, want: 'relay' },
  { name: 'freestun tcp 3478', cfg: { urls: 'turn:freestun.net:3478?transport=tcp', username: 'free', credential: 'free' }, want: 'relay' },
  { name: 'freestun tls 5350', cfg: { urls: 'turns:freestun.net:5350', username: 'free', credential: 'free' }, want: 'relay' },
  { name: 'expressturn udp', cfg: { urls: 'turn:relay1.expressturn.com:3478', username: 'efPU52K4SLOQ34W2QY', credential: '1TJPNFxHKXrZfelz' }, want: 'relay' },
];
(async () => {
  const browser = await puppeteer.launch({ executablePath: process.env.CHROME || '/usr/bin/google-chrome', args: ['--no-sandbox'] });
  const page = await browser.newPage();
  for (const s of servers) {
    const res = await page.evaluate(async (s) => {
      const pc = new RTCPeerConnection({ iceServers: [s.cfg], iceTransportPolicy: s.want === 'relay' ? 'relay' : 'all' });
      pc.createDataChannel('x');
      const types = new Set(); let err = '';
      pc.onicecandidate = e => { if (e.candidate && e.candidate.type) types.add(e.candidate.type); };
      pc.onicecandidateerror = e => { err = `${e.errorCode} ${e.errorText}`; };
      await pc.setLocalDescription(await pc.createOffer());
      await new Promise(r => { const t = setTimeout(r, 12000); pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') { clearTimeout(t); r(); } }; });
      pc.close();
      return { types: [...types], err };
    }, s);
    console.log(`TURNPROBE ${s.name}: ${res.types.includes(s.want) ? 'OK' : 'FAIL'} types=${res.types.join(',')} ${res.err}`);
  }
  await browser.close();
})();

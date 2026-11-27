// PDM standby DoH. readDnsBody and the single-question parser are adapted from
// the user-supplied dns-worker.zip (see README). No admin UI or remote lists.
const UPSTREAMS = new Set(['https://one.one.one.one/dns-query', 'https://blank.dnsforge.de/dns-query', 'https://unfiltered.joindns4.eu/dns-query']);
const headers = {'cache-control':'no-store', 'x-robots-tag':'noindex, nofollow', 'referrer-policy':'no-referrer', 'x-content-type-options':'nosniff'};
const reject = (status) => new Response(null, {status, headers});
async function readDnsBody(request, limit) {
  if (!request.body) return new Uint8Array();
  const reader=request.body.getReader(), chunks=[]; let size=0;
  try { for (;;) { const {done,value}=await reader.read(); if(done)break;
    size+=value.byteLength; if(size>limit){await reader.cancel();throw Error('size');} chunks.push(value);
  }} finally {reader.releaseLock();}
  const result=new Uint8Array(size);let offset=0;for(const c of chunks){result.set(c,offset);offset+=c.length;}return result;
}
function nameAt(bytes, start) {
  let pos=start, end=null;const labels=[],seen=new Set();
  for(let count=0;count<128;count++) {
    if(pos>=bytes.length||seen.has(pos))throw Error('name');seen.add(pos);
    const n=bytes[pos++];
    if(n===0)return {name:labels.join('.').toLowerCase(),end:end??pos};
    if((n&192)===192){if(pos>=bytes.length)throw Error('pointer');end??=pos+1;pos=((n&63)<<8)|bytes[pos];continue;}
    if(n>63||pos+n>bytes.length)throw Error('label');
    labels.push(String.fromCharCode(...bytes.subarray(pos,pos+n)));pos+=n;
    if(labels.join('.').length>253)throw Error('name');
  }throw Error('loop');
}
export function parseWire(bytes, response=false) {
  if(bytes.length<12||bytes.length>8192)throw Error('size');
  const dv=new DataView(bytes.buffer,bytes.byteOffset,bytes.byteLength),flags=dv.getUint16(2);
  if(Boolean(flags&32768)!==response||(flags&0x7800)||dv.getUint16(4)!==1)throw Error('header');
  const q=nameAt(bytes,12);let pos=q.end;
  if(pos+4>bytes.length||dv.getUint16(pos+2)!==1||dv.getUint16(pos)===0)throw Error('question');
  const type=dv.getUint16(pos);pos+=4;
  const question={name:q.name,type,id:dv.getUint16(0)};
  const records=[];
  for(let section=0;section<3;section++) {
    const count=dv.getUint16(6+section*2);if(count>256||(!response&&section<2&&count))throw Error('count');
    for(let i=0;i<count;i++) {
      const owner=nameAt(bytes,pos);pos=owner.end;
      if(pos+10>bytes.length)throw Error('rr');
      const type=dv.getUint16(pos),cls=dv.getUint16(pos+2),ttl=pos+4,length=dv.getUint16(pos+8),data=pos+10;
      if(data+length>bytes.length||(!response&&(type!==41||owner.name!==''||count!==1)))throw Error('rdata');
      records.push({type,cls,ttl,data,length,section});pos=data+length;
    }
  }
  if(pos!==bytes.length)throw Error('trailing');return {question,records,flags};
}
function address(value) {
  if(typeof value!=='string')throw Error('address');
  if(!value.includes(':')) {
    if(!/^(?:\d{1,3}\.){3}\d{1,3}$/.test(value))throw Error('ipv4');
    const a=value.split('.').map(Number);
    if(a.some(n=>n>255)||a[0]===0||a[0]===10||a[0]===127||a[0]>=224||a[0]===169&&a[1]===254||a[0]===172&&a[1]>=16&&a[1]<=31||a[0]===192&&a[1]===168)throw Error('nonpublic');
    return Uint8Array.from(a);
  }
  if(!/^[23][0-9a-f:]+$/i.test(value))throw Error('ipv6');
  const canonical=new URL('http://['+value+']/').hostname.slice(1,-1), halves=canonical.split('::');
  const left=halves[0]?halves[0].split(':'):[], right=halves[1]?halves[1].split(':'):[];
  const words=halves.length===2?[...left,...Array(8-left.length-right.length).fill('0'),...right]:left;
  if(words.length!==8)throw Error('ipv6');
  return Uint8Array.from(words.flatMap(x=>{const n=parseInt(x,16);return [n>>8,n&255];}));
}
function rulesFor(raw, name, now) {
  if(typeof raw!=='string'||raw.length>65536)throw Error('rules');
  const entries=JSON.parse(raw||'[]');if(!Array.isArray(entries)||entries.length>128)throw Error('rules');
  return entries.map(r=>{
    if(!r||typeof r.name!=='string'||r.name.length>253||!/^(?:[a-z0-9_-]+\.)+[a-z0-9_-]+$/i.test(r.name)||!Number.isFinite(r.expires)||r.expires>now+7*86400000)throw Error('rule');
    const from=address(r.from),to=address(r.to);if(from.length!==to.length)throw Error('family');
    return {...r,from,to};
  }).filter(r=>r.name.toLowerCase()===name&&r.expires>now);
}
export function rewrite(bytes, query, rawRules='[]', now=Date.now()) {
  const parsed=parseWire(bytes,true),q=parsed.question;
  if(q.id!==query.id||q.name!==query.name||q.type!==query.type)throw Error('mismatch');
  const rules=rulesFor(rawRules,q.name,now), out=Uint8Array.from(bytes),dv=new DataView(out.buffer);let changed=false;
  function patch(offset,length) {
    const r=rules.find(r=>r.from.length===length&&r.from.every((b,i)=>out[offset+i]===b));
    if(!r)return false;out.set(r.to,offset);changed=true;return true;
  }
  for(const rr of parsed.records) {
    if(rr.cls!==1||rr.section!==0)continue;let edited=false;
    if(rr.type===1&&rr.length===4||rr.type===28&&rr.length===16)edited=patch(rr.data,rr.length);
    if(rr.type===64||rr.type===65) {
      if(rr.length<3)throw Error('svcb');let pos=nameAt(out,rr.data+2).end;const end=rr.data+rr.length;
      if(pos>end)throw Error('svcb');let previous=-1;
      while(pos<end){if(pos+4>end)throw Error('svcb');const key=dv.getUint16(pos),size=dv.getUint16(pos+2);pos+=4;
        if(key<=previous||pos+size>end)throw Error('svcb');previous=key;
        const width=key===4?4:key===6?16:0;
        if(width){if(size===0||size%width)throw Error('hint');for(let i=pos;i<pos+size;i+=width)edited=patch(i,width)||edited;}
        pos+=size;
      }
    }
    if(edited)dv.setUint32(rr.ttl,Math.min(dv.getUint32(rr.ttl),30));
  }
  if(changed)out[3]&=~0x20; // Modified addresses are not authenticated DNSSEC data.
  return out; // Never suppress AAAA or change family; untouched packets stay identical.
}
export default {
  async fetch(request, env) {
    const url=new URL(request.url),token=env.DNS_PROFILE_TOKEN;
    if(env.ENABLED!=='true'||!/^([a-f0-9]{64})$/.test(token||'')||url.pathname!=='/dns-query/'+token)return reject(404);
    if(!env.DNS_LIMITER)return reject(503);
    try { if(!(await env.DNS_LIMITER.limit({key:request.headers.get('CF-Connecting-IP')||'unknown'})).success)return reject(429); }
    catch {return reject(503);}
    if(!['GET','POST'].includes(request.method))return reject(405);
    const upstream=env.UPSTREAM||'https://one.one.one.one/dns-query';if(!UPSTREAMS.has(upstream))return reject(503);
    let bytes,query;
    try {
      if(request.method==='POST') {
        if(url.search||request.headers.get('content-type')?.split(';')[0].trim().toLowerCase()!=='application/dns-message')return reject(415);
        bytes=await readDnsBody(request,4096);
      } else {
        const value=url.searchParams.get('dns');
        if([...url.searchParams.keys()].length!==1||!value||value.length>5462||!/^[A-Za-z0-9_-]+$/.test(value))return reject(400);
        bytes=Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
      }
      if(bytes.length>4096)throw Error('size');query=parseWire(bytes).question;
      rulesFor(env.RULES_JSON||'[]',query.name,Date.now());
    }catch{return reject(400);}
    const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),5000);
    try {
      const result=await fetch(upstream,{method:'POST',headers:{'content-type':'application/dns-message','accept':'application/dns-message'},body:bytes,redirect:'error',signal:controller.signal});
      if(!result.ok||result.headers.get('content-type')?.split(';')[0].trim().toLowerCase()!=='application/dns-message')return reject(502);
      const wire=await readDnsBody(result,8192);
      return new Response(rewrite(wire,query,env.RULES_JSON),{headers:{...headers,'content-type':'application/dns-message'}});
    }catch{return reject(502);}finally{clearTimeout(timer);}
  }
};

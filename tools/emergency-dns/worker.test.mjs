import test from 'node:test';
import assert from 'node:assert/strict';
import worker,{parseWire,rewrite} from './worker.mjs';
const token='a'.repeat(64),url='https://standby.invalid/dns-query/'+token;
const env={ENABLED:'true',DNS_PROFILE_TOKEN:token,DNS_LIMITER:{limit:async()=>({success:true})}};
function query(type=1){const name=Buffer.from([7,...Buffer.from('example'),3,...Buffer.from('com'),0]);const h=Buffer.alloc(12);h.writeUInt16BE(123,0);h.writeUInt16BE(256,2);h.writeUInt16BE(1,4);const t=Buffer.alloc(4);t.writeUInt16BE(type);t.writeUInt16BE(1,2);return Buffer.concat([h,name,t]);}
function answer(type=1,data=Buffer.from([104,16,0,1])){const q=query(type),h=Buffer.from(q);h.writeUInt16BE(0x81a0,2);h.writeUInt16BE(1,6);const rr=Buffer.alloc(12);rr.writeUInt16BE(0xc00c);rr.writeUInt16BE(type,2);rr.writeUInt16BE(1,4);rr.writeUInt32BE(300,6);rr.writeUInt16BE(data.length,10);return Buffer.concat([h,rr,data]);}
const now=1800000000000;
const rules=JSON.stringify([{name:'example.com',from:'104.16.0.1',to:'104.16.0.2',expires:now+60000}]);
const req=(body=query())=>new Request(url,{method:'POST',headers:{'content-type':'application/dns-message'},body});
test('off, unknown URL, missing token and missing limiter fail closed',async()=>{
 assert.equal((await worker.fetch(req(),{...env,ENABLED:'false'})).status,404);
 assert.equal((await worker.fetch(new Request(url+'wrong'),env)).status,404);
 assert.equal((await worker.fetch(req(),{...env,DNS_PROFILE_TOKEN:''})).status,404);
 assert.equal((await worker.fetch(req(),{...env,DNS_LIMITER:null})).status,503);
});
test('rate limit enforced before upstream access',async()=>{assert.equal((await worker.fetch(req(),{...env,DNS_LIMITER:{limit:async()=>({success:false})}})).status,429);});
test('query cannot select arbitrary upstream',async()=>{assert.equal((await worker.fetch(req(),{...env,UPSTREAM:'http://127.0.0.1'})).status,503);});
test('malformed DNS, QR, opcode, multiple questions and trailing bytes rejected',async()=>{
 const cases=[Buffer.alloc(11),Buffer.concat([query(),Buffer.from([0])])];
 for(const [offset,value] of [[2,0x8100],[2,0x0900],[4,2]]){const q=query();q.writeUInt16BE(value,offset);cases.push(q);}
 for(const q of cases)assert.equal((await worker.fetch(req(q),env)).status,400);
});
test('compressed pointer cycles rejected',()=>{const q=query();q[12]=192;q[13]=12;assert.throws(()=>parseWire(q));});
test('request body bound enforced',async()=>{assert.equal((await worker.fetch(req(Buffer.alloc(4097)),env)).status,400);});
test('matching rewrite clears AD, caps TTL and preserves question',()=>{
 const q=parseWire(query()).question,out=rewrite(answer(),q,rules,now),p=parseWire(out,true);
 assert.deepEqual([...out.slice(-4)],[104,16,0,2]);assert.equal(out[3]&32,0);
 assert.equal(new DataView(out.buffer).getUint32(p.records[0].ttl),30);assert.deepEqual(p.question,q);
});
test('no rule, expired rule and different domain preserve bytes and AD',()=>{
 const q=parseWire(query()).question,wire=answer();
 for(const raw of ['[]',rules.replace('example.com','other.example'),rules.replace(String(now+60000),String(now-1))])assert.deepEqual(Buffer.from(rewrite(wire,q,raw,now)),wire);
});
test('IPv4 rule never suppresses AAAA',()=>{
 const wire=answer(28,Buffer.from('26064700470000000000000000001111','hex'));
 assert.deepEqual(Buffer.from(rewrite(wire,parseWire(query(28)).question,rules,now)),wire);
});
test('IPv6 rewrite keeps family and DNS record count',()=>{
 const raw=JSON.stringify([{name:'example.com',from:'2606:4700:4700::1111',to:'2606:4700:4700::1001',expires:now+60000}]);
 const out=rewrite(answer(28,Buffer.from('26064700470000000000000000001111','hex')),parseWire(query(28)).question,raw,now);
 assert.equal(Buffer.from(out.slice(-16)).toString('hex'),'26064700470000000000000000001001');assert.equal(parseWire(out,true).records.length,1);
});
test('HTTPS ipv4hint rewritten with IPv6 hint left intact',()=>{
 const data=Buffer.from([0,1,0,0,4,0,4,104,16,0,1,0,6,0,16,...Buffer.from('26064700470000000000000000001111','hex')]);
 const out=rewrite(answer(65,data),parseWire(query(65)).question,rules,now);
 assert.deepEqual([...out.slice(-24,-20)],[104,16,0,2]);assert.equal(out[3]&32,0);
 assert.equal(Buffer.from(out.slice(-16)).toString('hex'),'26064700470000000000000000001111');
});
test('mismatched response and invalid/private/cross-family rules rejected',()=>{
 assert.throws(()=>rewrite(answer(),{...parseWire(query()).question,id:999}));
 for(const to of ['127.0.0.1','192.168.1.1','2606:4700::1'])assert.throws(()=>rewrite(answer(),parseWire(query()).question,rules.replace('104.16.0.2',to),now));
});
test('POST and GET relay wire format, no credential forwarded',async(t)=>{
 let calls=0;t.mock.method(globalThis,'fetch',async(target,options)=>{
  calls++;assert.equal(target,'https://one.one.one.one/dns-query');assert.equal(options.redirect,'error');
  assert.deepEqual(Buffer.from(options.body),query());assert(!JSON.stringify(options.headers).includes(token));
  return new Response(answer(),{headers:{'content-type':'application/dns-message'}});
 });
 for(const request of [req(),new Request(url+'?dns='+query().toString('base64url'))]) {
  const response=await worker.fetch(request,env);assert.equal(response.status,200);assert.equal(response.headers.get('cache-control'),'no-store');
  assert.deepEqual(Buffer.from(await response.arrayBuffer()),answer());
 }assert.equal(calls,2);
});
test('upstream failure, wrong content type and oversized response rejected',async(t)=>{
 let response=new Response('failure',{status:503});t.mock.method(globalThis,'fetch',async()=>response);
 assert.equal((await worker.fetch(req(),env)).status,502);
 response=new Response(answer(),{headers:{'content-type':'text/html'}});assert.equal((await worker.fetch(req(),env)).status,502);
 response=new Response(Buffer.alloc(8193),{headers:{'content-type':'application/dns-message'}});assert.equal((await worker.fetch(req(),env)).status,502);
});

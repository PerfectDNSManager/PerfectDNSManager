// Explicit opt-in live DNS check. No deployment and no production token.
import {randomBytes} from 'node:crypto';
import assert from 'node:assert/strict';
import worker,{parseWire} from './worker.mjs';
if(!process.argv.includes('--live'))throw Error('Pass --live to send example.com DNS queries');
const token=randomBytes(32).toString('hex');
for(const type of [1,28]) {
 const query=Buffer.from('123401000001000000000000076578616d706c6503636f6d0000010001','hex');query.writeUInt16BE(type,query.length-4);
 const env={ENABLED:'true',DNS_PROFILE_TOKEN:token,DNS_LIMITER:{limit:async()=>({success:true})}};
 const result=await worker.fetch(new Request('https://test.invalid/dns-query/'+token,{method:'POST',headers:{'content-type':'application/dns-message'},body:query}),env);
 assert.equal(result.status,200);const parsed=parseWire(new Uint8Array(await result.arrayBuffer()),true);
 assert.equal(parsed.flags&15,0);assert(parsed.records.some(r=>r.type===type&&r.section===0));console.log('PASS live DoH',type===1?'A':'AAAA');
}

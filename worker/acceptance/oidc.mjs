// Optional OpenID Provider fixture for the Java + MySQL acceptance program.
import assert from "node:assert/strict";
import { createHash, generateKeyPairSync, randomBytes, sign } from "node:crypto";
import { createServer } from "node:http";

export async function identityFixture(servers,listen,redirect) {
  const {privateKey,publicKey}=generateKeyPairSync("rsa",{modulusLength:2048});
  const key={...publicKey.export({format:"jwk"}),kid:"test-key",alg:"RS256",use:"sig"};
  const codes=new Map(),clientSecret=randomBytes(24).toString("hex");
  let issuer,claimsPatch={},exchanges=0,keyReads=0,validPkce=0;
  const hash=value=>createHash("sha256").update(value).digest("base64url");
  const encoded=value=>Buffer.from(JSON.stringify(value)).toString("base64url");
  const srv=createServer(async(req,res)=>{
    const url=new URL(req.url,issuer);
    if(url.pathname==="/authorize"){
      const p=url.searchParams;
      assert.equal(p.get("response_type"),"code");assert.equal(p.get("scope"),"openid");
      assert.equal(p.get("client_id"),"workbench");assert.equal(p.get("redirect_uri"),redirect);
      assert.equal(p.get("code_challenge_method"),"S256");
      const code=randomBytes(32).toString("hex");
      codes.set(code,{nonce:p.get("nonce"),challenge:p.get("code_challenge")});
      res.writeHead(302,{location:redirect+"?"+new URLSearchParams({code,state:p.get("state")})});res.end();return;
    }
    if(url.pathname==="/keys"){keyReads++;res.writeHead(200,{"content-type":"application/json"});res.end(JSON.stringify({keys:[key]}));return;}
    if(url.pathname!=="/token"){res.writeHead(404);res.end();return;}
    exchanges++;let body="";for await(const b of req)body+=b;
    const p=new URLSearchParams(body),grant=codes.get(p.get("code"));codes.delete(p.get("code"));
    if (!grant || grant.challenge!==hash(p.get("code_verifier")??"") || p.get("redirect_uri")!==redirect ||
        req.headers.authorization!=="Basic "+Buffer.from("workbench:"+clientSecret).toString("base64")){
      res.writeHead(401);res.end();return;
    }
    validPkce++;const now=Math.floor(Date.now()/1000);
    const content=encoded({alg:"RS256",kid:key.kid,typ:"JWT"})+"."+encoded({
      iss:issuer,sub:"Example-Subject",aud:"workbench",iat:now,exp:now+120,nonce:grant.nonce,...claimsPatch,
    });
    const token=content+"."+sign("sha256",Buffer.from(content),privateKey).toString("base64url");
    res.writeHead(200,{"content-type":"application/json"});res.end(JSON.stringify({id_token:token,token_type:"Bearer"}));
  });
  servers.push(srv);issuer="http://127.0.0.1:"+await listen(srv);
  const variables={OIDC_ENABLED:"true",OIDC_ISSUER:issuer,OIDC_AUTHORIZATION_ENDPOINT:issuer+"/authorize",
    OIDC_TOKEN_ENDPOINT:issuer+"/token",OIDC_JWKS_URI:issuer+"/keys",OIDC_CLIENT_ID:"workbench",OIDC_CLIENT_SECRET:clientSecret,OIDC_REDIRECT_URI:redirect};
  return {variables,async verify(origin,api,userId){
    assert.equal((await api("GET","/auth/oidc/status")).enabled,true);
    await api("PUT","/admin/oidc/mappings/"+userId,{subject:"Example-Subject"});
    async function begin(){
      const start=await fetch(origin+"/api/auth/oidc/start",{method:"POST",headers:{Origin:new URL(redirect).origin}});
      assert.equal(start.status,200);const cookies=start.headers.getSetCookie()[0];
      assert.match(cookies,/HttpOnly/i);assert.match(cookies,/Secure/i);
      const url=(await start.json()).data.authorizationUrl;
      const auth=await fetch(url,{redirect:"manual"});assert.equal(auth.status,302);
      return {body:Object.fromEntries(new URL(auth.headers.get("location")).searchParams),cookie:cookies.split(";")[0]};
    }
    async function exchange(flow,cookie=flow.cookie){
      return fetch(origin+"/api/auth/oidc/complete",{method:"POST",headers:{Origin:new URL(redirect).origin,Cookie:cookie,"Content-Type":"application/json"},
        body:JSON.stringify(flow.body)});
    }
    const valid=await begin();
    assert.equal((await exchange(valid,"")).status,401);
    const result=await exchange(valid);
    assert.equal(result.status,200,JSON.stringify({exchanges,validPkce,keyReads}));
    const session=(await result.json()).data;
    const me=await fetch(origin+"/api/auth/me",{headers:{Authorization:"Bearer "+session.token}});
    assert.equal((await me.json()).data.id,userId);
    assert.equal((await exchange(valid)).status,401);
    for(const wrong of [{nonce:"wrong"},{aud:"other"},{sub:"example-subject"},{iss:issuer+"/another"}]){
      claimsPatch=wrong;
      assert.equal((await exchange(await begin())).status,401);
    }
    claimsPatch={};
    await api("DELETE","/admin/oidc/mappings/"+userId);
    assert.equal((await exchange(await begin())).status,401);
    console.log("OIDC 验收通过：PKCE、浏览器绑定、nonce、issuer、audience、区分大小写的账号映射、撤销及重复回调。");
  }};
}

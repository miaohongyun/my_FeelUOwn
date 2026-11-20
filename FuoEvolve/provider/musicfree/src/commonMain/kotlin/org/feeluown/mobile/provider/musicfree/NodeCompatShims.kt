package org.feeluown.mobile.provider.musicfree

/**
 * 阶段 I2：Node 兼容模块的纯 JS 实现（在沙箱 Context 内以 CommonJS 模块形式求值）。
 *
 * 每个字符串是一个自包含 CommonJS 模块源码，形如 `module.exports = {...};`。
 * 仅实现 [NodeCompatModules.specs] 声明的支持范围；不提供 fs/process/net/vm/eval/require 递归能力。
 * crypto 的 md5/sha1/hmac 为纯 JS 标准实现（无随机源、无密钥托管），满足 audiomack OAuth1
 * （HmacSHA1 + Base64）与 navidrome（md5）真实调用面。
 */
object NodeCompatShims {

    /** UTF-8 编码为字节数组（JS 字符串 -> 0..255 数组）。 */
    private const val UTF8_ENCODE = """
        function __utf8Bytes(s){
          s = String(s); var out = [];
          for (var i=0;i<s.length;i++){
            var c = s.charCodeAt(i);
            if (c < 0x80) out.push(c);
            else if (c < 0x800) { out.push(0xC0|(c>>6)); out.push(0x80|(c&0x3F)); }
            else if (c >= 0xD800 && c <= 0xDBFF && i+1<s.length){
              var c2 = s.charCodeAt(++i);
              var cp = 0x10000 + (((c-0xD800)<<10) | (c2-0xDC00));
              out.push(0xF0|(cp>>18),0x80|((cp>>12)&0x3F),0x80|((cp>>6)&0x3F),0x80|(cp&0x3F));
            } else { out.push(0xE0|(c>>12),0x80|((c>>6)&0x3F),0x80|(c&0x3F)); }
          }
          return out;
        }
    """

    private const val HEX_BASE64 = """
        function __hex(bytes){ var h=''; for (var i=0;i<bytes.length;i++){ var x=bytes[i].toString(16); h += x.length===1?'0'+x:x; } return h; }
        var __B64='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
        function __b64(bytes){
          var out='';
          for (var i=0;i<bytes.length;i+=3){
            var a=bytes[i], b=i+1<bytes.length?bytes[i+1]:0, c=i+2<bytes.length?bytes[i+2]:0;
            var n=(a<<16)|(b<<8)|c;
            out += __B64[(n>>18)&63] + __B64[(n>>12)&63] + (i+1<bytes.length?__B64[(n>>6)&63]:'=') + (i+2<bytes.length?__B64[n&63]:'=');
          }
          return out;
        }
        function __b64Decode(s){
          s=String(s).replace(/[^A-Za-z0-9+/=]/g,''); var out=[];
          for (var i=0;i<s.length;i+=4){
            var n=(__B64.indexOf(s[i])<<18)|(__B64.indexOf(s[i+1])<<12)|
                  ((s[i+2]==='='?0:__B64.indexOf(s[i+2]))<<6)|(s[i+3]==='='?0:__B64.indexOf(s[i+3]));
            out.push((n>>16)&255); if(s[i+2]!=='=') out.push((n>>8)&255); if(s[i+3]!=='=') out.push(n&255);
          }
          return out;
        }
        function __hexDecode(s){ s=String(s); var out=[]; for(var i=0;i+1<s.length;i+=2) out.push(parseInt(s.substr(i,2),16)); return out; }
    """

    private const val MD5_JS = """
        function __md5(bytes){
          var s=[], K=[];
          for(var i=0;i<64;i++) K[i]=Math.floor(Math.abs(Math.sin(i+1))*4294967296);
          var S=[7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,
                 5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
                 4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,
                 6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21];
          var ml=bytes.length*8;
          bytes=bytes.slice(); bytes.push(0x80);
          while(bytes.length%64!==56) bytes.push(0);
          var hi=Math.floor(ml/4294967296), lo=ml>>>0;
          bytes.push(lo&255,(lo>>>8)&255,(lo>>>16)&255,(lo>>>24)&255,
                     hi&255,(hi>>>8)&255,(hi>>>16)&255,(hi>>>24)&255);
          var a0=0x67452301,b0=0xefcdab89,c0=0x98badcfe,d0=0x10325476;
          function rol(x,n){ return ((x<<n)|(x>>>(32-n)))>>>0; }
          for(var off=0;off<bytes.length;off+=64){
            var M=[];
            for(var j=0;j<16;j++) M[j]=(bytes[off+j*4]|bytes[off+j*4+1]<<8|bytes[off+j*4+2]<<16|bytes[off+j*4+3]<<24)>>>0;
            var A=a0,B=b0,C=c0,D=d0;
            for(var i=0;i<64;i++){
              var F,g;
              if(i<16){F=(B&C)|(~B&D);g=i;}
              else if(i<32){F=(D&B)|(~D&C);g=(5*i+1)%16;}
              else if(i<48){F=B^C^D;g=(3*i+5)%16;}
              else{F=C^(B|~D);g=(7*i)%16;}
              var tmp=(D)>>>0; D=C; C=B;
              B=(B+rol((A+F+K[i]+M[g])>>>0,S[i]))>>>0; A=tmp;
            }
            a0=(a0+A)>>>0;b0=(b0+B)>>>0;c0=(c0+C)>>>0;d0=(d0+D)>>>0;
          }
          var out=[];
          [a0,b0,c0,d0].forEach(function(v){ out.push(v&255,(v>>>8)&255,(v>>>16)&255,(v>>>24)&255); });
          return out;
        }
    """

    private const val SHA1_JS = """
        function __sha1(bytes){
          var ml=bytes.length*8;
          bytes=bytes.slice(); bytes.push(0x80);
          while(bytes.length%64!==56) bytes.push(0);
          var hi=Math.floor(ml/4294967296), lo=ml>>>0;
          // SHA-1 长度字段为 big-endian 64 位（高字节先），与 MD5 的 little-endian 相反。
          bytes.push((hi>>>24)&255,(hi>>>16)&255,(hi>>>8)&255,hi&255,
                     (lo>>>24)&255,(lo>>>16)&255,(lo>>>8)&255,lo&255);
          var h0=0x67452301,h1=0xEFCDAB89,h2=0x98BADCFE,h3=0x10325476,h4=0xC3D2E1F0;
          function rol(x,n){ return ((x<<n)|(x>>>(32-n)))>>>0; }
          for(var off=0;off<bytes.length;off+=64){
            var w=[];
            for(var t=0;t<16;t++) w[t]=(bytes[off+t*4]<<24|bytes[off+t*4+1]<<16|bytes[off+t*4+2]<<8|bytes[off+t*4+3])>>>0;
            for(t=16;t<80;t++) w[t]=rol(w[t-3]^w[t-8]^w[t-14]^w[t-16],1);
            var a=h0,b=h1,c=h2,d=h3,e=h4;
            for(t=0;t<80;t++){
              var f,k;
              if(t<20){f=(b&c)|(~b&d);k=0x5A827999;}
              else if(t<40){f=b^c^d;k=0x6ED9EBA1;}
              else if(t<60){f=(b&c)|(b&d)|(c&d);k=0x8F1BBCDC;}
              else{f=b^c^d;k=0xCA62C1D6;}
              var tmp=rol(a,5)>>>0; tmp=(tmp+f+e+k+w[t])>>>0; e=d;d=c;c=rol(b,30)>>>0;b=a;a=tmp;
            }
            h0=(h0+a)>>>0;h1=(h1+b)>>>0;h2=(h2+c)>>>0;h3=(h3+d)>>>0;h4=(h4+e)>>>0;
          }
          var out=[];
          [h0,h1,h2,h3,h4].forEach(function(v){ out.push((v>>>24)&255,(v>>>16)&255,(v>>>8)&255,v&255); });
          return out;
        }
    """

    private const val HMAC_JS = """
        function __hmacSha1(keyBytes, msgBytes){
          var block=64, k=keyBytes.slice();
          if(k.length>block) k=__sha1(k);
          while(k.length<block) k.push(0);
          var ipad=[],opad=[];
          for(var i=0;i<block;i++){ ipad.push(k[i]^0x36); opad.push(k[i]^0x5C); }
          var inner=ipad.concat(msgBytes);
          var ih=__sha1(inner);
          return __sha1(opad.concat(ih));
        }
    """

    val cryptoJs: String = """
        $UTF8_ENCODE
        $HEX_BASE64
        $MD5_JS
        $SHA1_JS
        $HMAC_JS
        // audiomack 用法：CryptoJS.HmacSHA1(l, secret+'&').toString(CryptoJS.enc.Base64)
        function HmacSHA1(message, key){
          var kb = (key && key.words!==undefined) ? (function(){var o=[];key.words.forEach(function(w){o.push((w>>>24)&255,(w>>>16)&255,(w>>>8)&255,w&255);});return o.slice(0,key.sigBytes||o.length);})() : __utf8Bytes(key);
          var mb = (message && message.words!==undefined) ? (function(){var o=[];message.words.forEach(function(w){o.push((w>>>24)&255,(w>>>16)&255,(w>>>8)&255,w&255);});return o.slice(0,message.sigBytes||o.length);})() : __utf8Bytes(message);
          var digest = __hmacSha1(kb, mb);
          return {
            sigBytes: digest.length,
            words: (function(){var w=[];for(var i=0;i<digest.length;i+=4) w.push((digest[i]<<24|(digest[i+1]||0)<<16|(digest[i+2]||0)<<8|(digest[i+3]||0))>>>0);return w;})(),
            toString: function(enc){
              if (enc && enc.__kind === 'Base64') return __b64(digest);
              if (enc && enc.__kind === 'Hex') return __hex(digest);
              return __hex(digest);
            }
          };
        }
        function MD5(message){ var b=__utf8Bytes(message); var d=__md5(b); return { toString:function(enc){ return (enc&&enc.__kind==='Base64')?__b64(d):__hex(d); } }; }
        function SHA1(message){ var b=__utf8Bytes(message); var d=__sha1(b); return { toString:function(enc){ return (enc&&enc.__kind==='Base64')?__b64(d):__hex(d); } }; }
        module.exports = {
          HmacSHA1: HmacSHA1, MD5: MD5, SHA1: SHA1,
          enc: { Base64:{__kind:'Base64'}, Hex:{__kind:'Hex'}, Utf8:{__kind:'Utf8'} }
        };
    """.trimIndent()

    val querystringJs: String = """
        function parse(qs){
          var out={}; qs=String(qs||'').replace(/^\?/,'');
          qs.split('&').forEach(function(pair){
            if(!pair) return;
            var i=pair.indexOf('='); var k=i<0?pair:pair.slice(0,i); var v=i<0?'':pair.slice(i+1);
            try { k=decodeURIComponent(k.replace(/\+/g,' ')); v=decodeURIComponent(v.replace(/\+/g,' ')); } catch(e){}
            out[k]=v;
          });
          return out;
        }
        function stringify(obj){
          var parts=[];
          for (var k in obj){ if(!Object.prototype.hasOwnProperty.call(obj,k)) continue;
            parts.push(encodeURIComponent(k)+'='+encodeURIComponent(obj[k])); }
          return parts.join('&');
        }
        module.exports={parse:parse,stringify:stringify};
    """.trimIndent()

    val urlJs: String = """
        function parse(u){
          var m=/^([a-zA-Z][a-zA-Z0-9+.-]*):\/\/([^\/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$/.exec(String(u||''));
          if(!m) return {protocol:null,host:null,pathname:null,search:null,hash:null,query:null,href:String(u||'')};
          var query=null; var search=m[4]||null;
          if(search){ query={}; search.slice(1).split('&').forEach(function(p){ if(!p)return; var i=p.indexOf('='); var k=i<0?p:p.slice(0,i); var v=i<0?'':p.slice(i+1); try{k=decodeURIComponent(k);v=decodeURIComponent(v);}catch(e){} query[k]=v; }); }
          return {protocol:m[1],host:m[2],pathname:m[3]||'/',search:search,hash:m[5]||null,query:query,href:String(u)};
        }
        function URLSearchParams(init){
          var self=this; self._m={};
          if(typeof init==='string') init.replace(/^\?/,'').split('&').forEach(function(p){ if(!p)return; var i=p.indexOf('='); self._m[decodeURIComponent(i<0?p:p.slice(0,i))]=decodeURIComponent(i<0?'':p.slice(i+1)); });
          else if(init && typeof init==='object') for(var k in init) self._m[k]=String(init[k]);
        }
        URLSearchParams.prototype.get=function(k){ return Object.prototype.hasOwnProperty.call(this._m,k)?this._m[k]:null; };
        URLSearchParams.prototype.set=function(k,v){ this._m[k]=String(v); };
        URLSearchParams.prototype.has=function(k){ return Object.prototype.hasOwnProperty.call(this._m,k); };
        URLSearchParams.prototype.toString=function(){ var a=[]; for(var k in this._m) a.push(encodeURIComponent(k)+'='+encodeURIComponent(this._m[k])); return a.join('&'); };
        module.exports={parse:parse,URLSearchParams:URLSearchParams};
    """.trimIndent()

    val pathJs: String = """
        function basename(p){ p=String(p||''); var i=p.lastIndexOf('/'); return i<0?p:p.slice(i+1); }
        function dirname(p){ p=String(p||''); var i=p.lastIndexOf('/'); return i<0?'.':(i===0?'/':p.slice(0,i)); }
        function extname(p){ var b=basename(p), i=b.lastIndexOf('.'); return i<=0?'':b.slice(i); }
        function join(){ var segs=[]; for(var i=0;i<arguments.length;i++){ var s=String(arguments[i]||''); if(s) segs.push(s); } return segs.join('/'); }
        module.exports={basename:basename,dirname:dirname,extname:extname,join:join};
    """.trimIndent()

    val eventsJs: String = """
        function EventEmitter(){ this._e={}; }
        EventEmitter.prototype.on=function(name,fn){ (this._e[name]=this._e[name]||[]).push(fn); return this; };
        EventEmitter.prototype.once=function(name,fn){ var self=this; function g(){ self.off(name,g); fn.apply(self,arguments); } g._orig=fn; return this.on(name,g); };
        EventEmitter.prototype.off=function(name,fn){ var a=this._e[name]||[]; this._e[name]=a.filter(function(f){ return f!==fn && f._orig!==fn; }); return this; };
        EventEmitter.prototype.emit=function(name){ var a=(this._e[name]||[]).slice(), args=Array.prototype.slice.call(arguments,1); for(var i=0;i<a.length;i++) a[i].apply(this,args); return a.length>0; };
        EventEmitter.prototype.removeAllListeners=function(name){ if(name) delete this._e[name]; else this._e={}; return this; };
        EventEmitter.prototype.listenerCount=function(name){ return (this._e[name]||[]).length; };
        module.exports={EventEmitter:EventEmitter};
    """.trimIndent()

    val utilJs: String = """
        function format(f){
          var args=Array.prototype.slice.call(arguments,1), i=0;
          return String(f).replace(/%[sdj%]/g,function(m){
            if(m==='%%') return '%'; if(i>=args.length) return m; var v=args[i++];
            if(m==='%j'){ try{return JSON.stringify(v);}catch(e){return '[Circular]';} }
            return String(v);
          });
        }
        function inspect(v){ try{return typeof v==='string'?v:JSON.stringify(v);}catch(e){return String(v);} }
        module.exports={format:format,inspect:inspect};
    """.trimIndent()

    val heJs: String = """
        var NAMED={'amp':'&','lt':'<','gt':'>','quot':String.fromCharCode(34),'apos':String.fromCharCode(39),'nbsp':'\u00a0','copy':'\u00a9','reg':'\u00ae','hellip':'\u2026','mdash':'\u2014','ndash':'\u2013'};
        function decode(s){
          return String(s||'').replace(/&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);/g,function(m,e){
            if(e[0]==='#'){ var cp=e[1]==='x'||e[1]==='X'?parseInt(e.slice(2),16):parseInt(e.slice(1),10); try{return String.fromCharCode(cp);}catch(x){return m;} }
            return Object.prototype.hasOwnProperty.call(NAMED,e)?NAMED[e]:m;
          });
        }
        function encode(s){ return String(s||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&apos;'); }
        module.exports={decode:decode,encode:encode};
    """.trimIndent()

    val dayjsJs: String = """
        function pad(n){ return n<10?'0'+n:''+n; }
        function dayjs(ts){
          var d = (ts===undefined||ts===null)? new Date(0) : new Date(typeof ts==='number'?ts*1000:ts);
          return {
            format: function(fmt){
              var y=d.getUTCFullYear(),mo=d.getUTCMonth()+1,da=d.getUTCDate(),h=d.getUTCHours(),mi=d.getUTCMinutes(),s=d.getUTCSeconds();
              return String(fmt||'YYYY-MM-DD')
                .replace(/YYYY/g,''+y).replace(/MM/g,pad(mo)).replace(/DD/g,pad(da))
                .replace(/HH/g,pad(h)).replace(/mm/g,pad(mi)).replace(/ss/g,pad(s));
            },
            unix: function(){ return Math.floor(d.getTime()/1000); },
            valueOf: function(){ return d.getTime(); }
          };
        }
        dayjs.unix=function(ts){ return dayjs(ts); };
        module.exports=dayjs; module.exports.dayjs=dayjs;
    """.trimIndent()

    val bufferJs: String = """
        $UTF8_ENCODE
        $HEX_BASE64
        function BufferFrom(value, enc){
          enc=enc||'utf8'; var bytes;
          if(enc==='base64') bytes=__b64Decode(value);
          else if(enc==='hex') bytes=__hexDecode(value);
          else bytes=__utf8Bytes(value);
          return { bytes:bytes, length:bytes.length, toString:function(toEnc){
            toEnc=toEnc||'utf8';
            if(toEnc==='base64') return __b64(bytes);
            if(toEnc==='hex') return __hex(bytes);
            var out=''; for(var i=0;i<bytes.length;i++) out+=String.fromCharCode(bytes[i]); return out;
          }};
        }
        module.exports={from:BufferFrom,byteLength:function(s,enc){ return BufferFrom(s,enc).length; }};
    """.trimIndent()

    /** cheerio 只读子集：$('sel').text()/attr()/find()/eq()/length。基于正则提取，不执行脚本。 */
    val cheerioJs: String = """
        function load(html){
          var src=String(html||'');
          function stripTags(s){ return s.replace(/<[^>]*>/g,'').replace(/&nbsp;/g,' ').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>').trim(); }
          function findTag(s, tag){ var re=new RegExp('<'+tag+'\\b[^>]*>([\\s\\S]*?)<\\/'+tag+'>','i'); var m=re.exec(s); return m?m[1]:''; }
          function Selection(nodes){ this._n=nodes||[]; }
          Object.defineProperty(Selection.prototype,'length',{get:function(){ return this._n.length; }});
          Selection.prototype.text=function(){ return stripTags(this._n.join(' ')); };
          Selection.prototype.attr=function(name){ var s=this._n[0]||''; var m=new RegExp('\\b'+name+'\\s*=\\s*["\\x27]([^"\\x27]*)["\\x27]','i').exec(s); return m?m[1]:undefined; };
          Selection.prototype.find=function(sel){ var nodes=[]; this._n.forEach(function(n){ var t=findTag(n,sel); if(t) nodes.push(t); }); return new Selection(nodes); };
          Selection.prototype.eq=function(i){ return new Selection(i<0?[]:[this._n[i]].filter(Boolean)); };
          Selection.prototype.html=function(){ return this._n[0]||''; };
          return function(sel){
            if(sel==='html'||sel==='body') return new Selection([src]);
            var re=new RegExp('<'+sel+'\\b[^>]*>([\\s\\S]*?)<\\/'+sel+'>','ig'), out=[], m;
            while((m=re.exec(src))!==null){ out.push(m[0]); if(out.length>2000) break; }
            return new Selection(out);
          };
        }
        module.exports={load:load};
    """.trimIndent()

    /** 名称 -> 模块源码；供 ModuleRegistry 登记（sha 由注册时计算绑定）。 */
    val all: Map<String, String> = mapOf(
        "events" to eventsJs,
        "querystring" to querystringJs,
        "url" to urlJs,
        "path" to pathJs,
        "util" to utilJs,
        "crypto-js" to cryptoJs,
        "buffer" to bufferJs,
        "dayjs" to dayjsJs,
        "he" to heJs,
        "cheerio" to cheerioJs,
    )
}

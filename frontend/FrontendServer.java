import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Сервер разработки frontend и reverse proxy. Не содержит бизнес-логику. JDK 17+. */
public class FrontendServer {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args.length>0?args[0]:"frontend").toAbsolutePath().normalize();
        int port=args.length>1?Integer.parseInt(args[1]):5500;
        HttpClient client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/",ex->{
            try {
                if(ex.getRequestURI().getPath().startsWith("/api/")) {
                    byte[] body=ex.getRequestBody().readNBytes(22*1024*1024+1);
                    if(body.length>22*1024*1024){reply(ex,413,"application/json","{\"message\":\"Слишком большой запрос\"}".getBytes());return;}
                    HttpRequest.Builder req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080"+ex.getRequestURI().toASCIIString())).timeout(java.time.Duration.ofMinutes(2));
                    Set<String> skip=Set.of("host","connection","content-length","expect","upgrade","http2-settings","transfer-encoding");
                    ex.getRequestHeaders().forEach((k,v)->{if(!skip.contains(k.toLowerCase(Locale.ROOT)))v.forEach(x->req.header(k,x));});
                    var response=client.send(req.method(ex.getRequestMethod(),body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofInputStream());
                    response.headers().map().forEach((k,v)->{if(!Set.of("transfer-encoding","connection","content-length").contains(k))ex.getResponseHeaders().put(k,v);});
                    ex.sendResponseHeaders(response.statusCode(),0);
                    try(InputStream in=response.body()){in.transferTo(ex.getResponseBody());}
                } else {
                    if(!ex.getRequestMethod().equals("GET")&&!ex.getRequestMethod().equals("HEAD")){reply(ex,405,"text/plain","Method not allowed".getBytes());return;}
                    String name=ex.getRequestURI().getPath();if(name.equals("/"))name="/index.html";
                    Path file=root.resolve(name.substring(1)).normalize();
                    String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1):"";
                    Map<String,String> types=Map.of("html","text/html; charset=utf-8","css","text/css; charset=utf-8","js","text/javascript; charset=utf-8","png","image/png","jpg","image/jpeg","svg","image/svg+xml","woff2","font/woff2","ttf","font/ttf","otf","font/otf");
                    if(!file.startsWith(root)||!Files.isRegularFile(file)||!types.containsKey(ext)){reply(ex,404,"text/plain","Not found".getBytes());return;}
                    ex.getResponseHeaders().set("X-Content-Type-Options","nosniff");
                    ex.getResponseHeaders().set("Referrer-Policy","same-origin");
                    ex.getResponseHeaders().set("Permissions-Policy","camera=(), microphone=(), geolocation=(), payment=()");
                    ex.getResponseHeaders().set("Cross-Origin-Resource-Policy","same-origin");
                    ex.getResponseHeaders().set("Cache-Control",ext.equals("html")?"no-store":"public, max-age=3600");
                    ex.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; img-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'; form-action 'self'");
                    reply(ex,200,types.get(ext),Files.readAllBytes(file));
                }
            } catch(Exception e) {try{reply(ex,502,"application/json; charset=utf-8","{\"message\":\"Backend не отвечает. Подождите запуска Java-сервера.\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
            finally {ex.close();}
        });
        server.setExecutor(Executors.newFixedThreadPool(12));server.start();
        System.out.println("SkyShelf frontend: http://localhost:"+port+"  (Ctrl+C to stop)");
    }
    static void reply(HttpExchange ex,int status,String type,byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type",type);
        if(ex.getRequestMethod().equals("HEAD")){ex.sendResponseHeaders(status,-1);return;}
        ex.sendResponseHeaders(status,data.length);ex.getResponseBody().write(data);
    }
}

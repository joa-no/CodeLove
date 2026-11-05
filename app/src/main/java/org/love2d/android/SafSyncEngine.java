package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sincronizacao conservadora entre a copia local e a arvore SAF autorizada.
 * O baseline e salvo somente depois que os dois lados foram atualizados.
 */
final class SafSyncEngine {
    static final int INITIAL_NONE = 0;
    static final int INITIAL_LOCAL_TO_EXTERNAL = 1;
    static final int INITIAL_EXTERNAL_TO_LOCAL = 2;
    static final int CONFLICT_NONE = 0;
    static final int CONFLICT_KEEP_LOCAL = 1;
    static final int CONFLICT_KEEP_EXTERNAL = 2;

    static final class Result {
        final boolean success;
        final boolean needsInitialChoice;
        final boolean needsConflictChoice;
        final String message;
        private Result(boolean ok, boolean initial, boolean conflict, String text) {
            success = ok; needsInitialChoice = initial; needsConflictChoice = conflict; message = text;
        }
        static Result ok(String text) { return new Result(true, false, false, text); }
        static Result choice() { return new Result(false, true, false, ""); }
        static Result conflicts(String text) { return new Result(false, false, true, text); }
        static Result error(String text) { return new Result(false, false, false, text); }
    }

    private static final String PREFS = "codelove_saf";
    private static final String KEY_PROJECTS = "projects";
    private static final String KEY_BASELINE = "syncBaseline";
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_DEPTH = 32;

    private static final class Entry {
        final String path;
        final boolean directory;
        final String hash;
        final String documentId;
        final String mime;
        Entry(String p, boolean d, String h, String id, String m) {
            path=p; directory=d; hash=h; documentId=id; mime=m;
        }
    }

    private SafSyncEngine() {}

    static Result synchronize(Activity activity, File workingRoot, int initialDirection) {
        return synchronize(activity, workingRoot, initialDirection, CONFLICT_NONE);
    }

    static Result synchronize(Activity activity, File workingRoot, int initialDirection, int conflictDirection) {
        try {
            JSONObject registration = findRegistration(activity, workingRoot);
            if (registration == null) return Result.error("Nao encontrei o vinculo SAF desta copia local.");
            Uri treeUri = Uri.parse(registration.getString("uri"));
            String rootId = registration.getString("projectDocumentId");
            if (!SafProjectManager.hasPersistedReadWritePermission(activity, treeUri)) {
                return Result.error("A autorizacao da pasta externa foi revogada. Adicione novamente a pasta para conceder acesso.");
            }

            Map<String, Entry> local = scanLocal(workingRoot);
            Map<String, Entry> remote = scanRemote(activity, treeUri, rootId);
            JSONObject baselineJson = registration.optJSONObject(KEY_BASELINE);

            Map<String, Entry> desired = new HashMap<String, Entry>();
            Map<String, String> sources = new HashMap<String, String>();

            if (baselineJson == null) {
                if (sameSnapshot(local, remote)) {
                    desired.putAll(local);
                    sourcesFrom(desired, sources, "local");
                } else if (initialDirection == INITIAL_NONE) {
                    return Result.choice();
                } else {
                    Map<String, Entry> source = initialDirection == INITIAL_LOCAL_TO_EXTERNAL ? local : remote;
                    desired.putAll(source);
                    sourcesFrom(desired, sources,
                        initialDirection == INITIAL_LOCAL_TO_EXTERNAL ? "local" : "remote");
                }
            } else {
                Map<String, Entry> baseline = decodeBaseline(baselineJson);
                Set<String> paths = new HashSet<String>();
                paths.addAll(baseline.keySet()); paths.addAll(local.keySet()); paths.addAll(remote.keySet());
                ArrayList<String> conflicts = new ArrayList<String>();
                for (String path : paths) {
                    Entry b=baseline.get(path), l=local.get(path), r=remote.get(path);
                    boolean lc=!sameState(b,l), rc=!sameState(b,r);
                    if (lc && rc && !sameState(l,r)) {
                        if (conflictDirection == CONFLICT_NONE) {
                            conflicts.add(path);
                            continue;
                        }
                        Entry chosenConflict = conflictDirection == CONFLICT_KEEP_LOCAL ? l : r;
                        if (chosenConflict != null) {
                            desired.put(path, chosenConflict);
                            sources.put(path, conflictDirection == CONFLICT_KEEP_LOCAL ? "local" : "remote");
                        }
                        continue;
                    }
                    Entry chosen;
                    String source;
                    if (lc) { chosen=l; source="local"; }
                    else if (rc) { chosen=r; source="remote"; }
                    else { chosen=b; source="local"; }
                    if (chosen != null) {
                        desired.put(path, chosen);
                        if (chosen.directory) sources.put(path, source);
                        else if (l != null && sameState(chosen,l)) sources.put(path,"local");
                        else sources.put(path,"remote");
                    }
                }
                if (!conflicts.isEmpty()) {
                    StringBuilder message = new StringBuilder("Foram encontrados conflitos nos seguintes caminhos:\\n\\n");
                    for (String path : conflicts) message.append("• ").append(path).append('\\n');
                    message.append("\\nEscolha qual versao manter somente nesses caminhos. As demais alteracoes sem conflito serao mescladas normalmente.");
                    return Result.conflicts(message.toString());
                }
            }

            applyLocal(activity, workingRoot, desired, sources, remote);
            applyRemote(activity, treeUri, rootId, desired, sources, local, remote);
            Map<String, Entry> finalLocal = scanLocal(workingRoot);
            Map<String, Entry> finalRemote = scanRemote(activity, treeUri, rootId);
            if (!sameSnapshot(finalLocal, finalRemote)) {
                return Result.error("A sincronizacao foi aplicada parcialmente, mas os dois lados ainda estao diferentes. Seus arquivos foram preservados. Tente sincronizar novamente.");
            }
            if (!saveBaseline(activity, treeUri, finalLocal)) {
                return Result.error("Os arquivos foram sincronizados, mas nao consegui salvar o ponto de referencia. A proxima sincronizacao pedira uma verificacao inicial.");
            }
            return Result.ok("Sincronizacao concluida. Os arquivos locais e externos estao iguais.");
        } catch (SecurityException e) {
            return Result.error("O Android negou acesso a pasta externa. Verifique a autorizacao e tente novamente.");
        } catch (Exception e) {
            android.util.Log.e("CodeLove", "Falha na sincronizacao SAF", e);
            return Result.error("Nao foi possivel concluir a sincronizacao: "
                + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                + "\n\nNenhum arquivo local e apagado automaticamente em caso de falha.");
        }
    }

    private static JSONObject findRegistration(Context context, File root) throws Exception {
        String canonical=root.getCanonicalPath();
        JSONArray items=new JSONArray(context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_PROJECTS,"[]"));
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item!=null && canonical.equals(new File(item.optString("workingPath","")).getCanonicalPath())) return item;
        }
        return null;
    }

    private static Map<String,Entry> scanLocal(File root) throws Exception {
        Map<String,Entry> out=new HashMap<String,Entry>();
        walkLocal(root,root,"",0,out);
        return out;
    }

    private static void walkLocal(File root, File dir, String prefix, int depth, Map<String,Entry> out) throws Exception {
        if(depth>MAX_DEPTH || out.size()>MAX_ENTRIES) throw new IOException("O projeto excede os limites de seguranca da sincronizacao.");
        File[] children=dir.listFiles();
        if(children==null) throw new IOException("Nao consegui listar a copia local.");
        for(File f:children){
            if(isSymlink(f)) throw new IOException("A sincronizacao foi interrompida porque encontrou um link simbolico: "+f.getName());
            String path=prefix.isEmpty()?f.getName():prefix+"/"+f.getName();
            if(f.isDirectory()){
                out.put(path,new Entry(path,true,"",null,DocumentsContract.Document.MIME_TYPE_DIR));
                walkLocal(root,f,path,depth+1,out);
            } else if(f.isFile()){
                out.put(path,new Entry(path,false,hashFile(f),null,"application/octet-stream"));
            }
            if(out.size()>MAX_ENTRIES) throw new IOException("O projeto possui arquivos demais para sincronizar.");
        }
    }

    private static Map<String,Entry> scanRemote(Activity a, Uri tree, String rootId) throws Exception {
        Map<String,Entry> out=new HashMap<String,Entry>();
        walkRemote(a,tree,rootId,"",0,out);
        return out;
    }

    private static void walkRemote(Activity a,Uri tree,String parentId,String prefix,int depth,Map<String,Entry> out)throws Exception{
        if(depth>MAX_DEPTH || out.size()>MAX_ENTRIES) throw new IOException("O projeto externo excede os limites de seguranca da sincronizacao.");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parentId);
        Cursor c=a.getContentResolver().query(children,new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null);
        if(c==null) throw new IOException("O Android nao conseguiu listar uma pasta externa.");
        try{
            int idc=c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nc=c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mc=c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            if(idc<0||nc<0||mc<0) throw new IOException("O provedor externo nao informou os dados necessarios.");
            while(c.moveToNext()){
                String id=c.getString(idc),name=c.getString(nc),mime=c.getString(mc);
                if(id==null||!safeName(name)) throw new IOException("A pasta externa contem um nome invalido.");
                String path=prefix.isEmpty()?name:prefix+"/"+name;
                boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if(dir){
                    out.put(path,new Entry(path,true,"",id,mime));
                    walkRemote(a,tree,id,path,depth+1,out);
                }else{
                    Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                    out.put(path,new Entry(path,false,hashStream(a.getContentResolver().openInputStream(doc)),id,mime));
                }
                if(out.size()>MAX_ENTRIES) throw new IOException("O projeto possui arquivos demais para sincronizar.");
            }
        }finally{c.close();}
    }

    private static void applyLocal(Activity a,File root,Map<String,Entry> desired,Map<String,String> sources,Map<String,Entry> remote)throws Exception{
        ArrayList<String> current=new ArrayList<String>();
        collectLocalPaths(root,root,"",current);
        Collections.sort(current,(x,y)->Integer.compare(depth(y),depth(x)));
        for(String p:current) if(!desired.containsKey(p)) FileOps.deleteRecursive(new File(root,p));
        ArrayList<String> paths=new ArrayList<String>(desired.keySet());
        Collections.sort(paths,(x,y)->Integer.compare(depth(x),depth(y)));
        for(String p:paths){
            Entry e=desired.get(p); File target=new File(root,p);
            ensureInside(root,target);
            if(e.directory){
                if(target.exists()&&!target.isDirectory()&&!target.delete())throw new IOException("Nao consegui substituir o arquivo local por uma pasta: "+p+".");
                if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Nao consegui criar a pasta local "+p+".");
            }
            else{
                if(target.isDirectory()&&!FileOps.deleteRecursive(target))throw new IOException("Nao consegui substituir a pasta local por um arquivo: "+p+".");
                Entry existing=scanEntryLocal(target,p);
                if(existing!=null && sameState(existing,e)) continue;
                File parent=target.getParentFile();
                if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Nao consegui criar a pasta de "+p+".");
                File temp=new File(parent,target.getName()+".codelove-sync.tmp");
                try(InputStream in=openSource(a,root,p,sources.get(p),remote);FileOutputStream out=new FileOutputStream(temp)){
                    copy(in,out);out.flush();out.getFD().sync();
                }
                if(target.exists()&&!target.delete()){temp.delete();throw new IOException("Nao consegui substituir "+p+".");}
                if(!temp.renameTo(target)){temp.delete();throw new IOException("Nao consegui publicar "+p+".");}
            }
        }
    }

    private static void applyRemote(Activity a,Uri tree,String rootId,Map<String,Entry> desired,Map<String,String> sources,Map<String,Entry> local,Map<String,Entry> remote)throws Exception{
        ArrayList<String> old=new ArrayList<String>(remote.keySet());
        Collections.sort(old,(x,y)->Integer.compare(depth(y),depth(x)));
        for(String p:old) if(!desired.containsKey(p)){
            Entry e=remote.get(p);
            Uri u=DocumentsContract.buildDocumentUriUsingTree(tree,e.documentId);
            if(!DocumentsContract.deleteDocument(a.getContentResolver(),u)) throw new IOException("O provedor nao conseguiu excluir "+p+".");
        }
        Map<String,String> ids=new HashMap<String,String>();
        ids.put("",rootId);
        ArrayList<String> paths=new ArrayList<String>(desired.keySet());
        Collections.sort(paths,(x,y)->Integer.compare(depth(x),depth(y)));
        for(String p:paths){
            Entry wanted=desired.get(p), existing=remote.get(p);
            int slash=p.lastIndexOf('/');
            String parent=slash<0?"":p.substring(0,slash);
            String name=slash<0?p:p.substring(slash+1);
            String parentId=ids.get(parent);
            if(parentId==null) throw new IOException("Pasta superior ausente durante a sincronizacao: "+parent);
            String id=existing==null?null:existing.documentId;
            if(existing!=null && existing.directory!=wanted.directory){
                Uri oldUri=DocumentsContract.buildDocumentUriUsingTree(tree,existing.documentId);
                if(!DocumentsContract.deleteDocument(a.getContentResolver(),oldUri))
                    throw new IOException("Nao consegui substituir o item externo "+p+" por outro tipo.");
                id=null;
            }
            if(wanted.directory){
                if(id==null){
                    Uri made=DocumentsContract.createDocument(a.getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(tree,parentId),
                        DocumentsContract.Document.MIME_TYPE_DIR,name);
                    if(made==null)throw new IOException("Nao consegui criar a pasta externa "+p+".");
                    id=DocumentsContract.getDocumentId(made);
                }
                ids.put(p,id);
            }else{
                if(id==null){
                    Uri made=DocumentsContract.createDocument(a.getContentResolver(),
                        DocumentsContract.buildDocumentUriUsingTree(tree,parentId),
                        wanted.mime==null?"application/octet-stream":wanted.mime,name);
                    if(made==null)throw new IOException("Nao consegui criar o arquivo externo "+p+".");
                    id=DocumentsContract.getDocumentId(made);
                }
                boolean needsWrite=existing==null||!sameState(existing,wanted);
                if(needsWrite){
                    Uri target=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                    try(InputStream in=openSource(a,new File(findWorkingPath(a,tree)),p,sources.get(p),remote);
                        OutputStream out=a.getContentResolver().openOutputStream(target,"wt")){
                        if(out==null)throw new IOException("Nao consegui gravar o arquivo externo "+p+".");
                        copy(in,out);out.flush();
                    }
                }
            }
        }
    }

    private static File findWorkingPath(Context c,Uri tree)throws Exception{
        JSONArray a=new JSONArray(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY_PROJECTS,"[]"));
        for(int i=0;i<a.length();i++){JSONObject o=a.optJSONObject(i);if(o!=null&&tree.toString().equals(o.optString("uri")))return new File(o.getString("workingPath"));}
        throw new IOException("Copia local nao encontrada.");
    }

    private static InputStream openSource(Activity a,File root,String path,String source,Map<String,Entry> remote)throws Exception{
        if("remote".equals(source)){
            Entry e=remote.get(path);if(e==null)throw new IOException("Origem externa ausente: "+path);
            JSONObject reg=findRegistration(a,root);Uri tree=Uri.parse(reg.getString("uri"));
            return a.getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree,e.documentId));
        }
        return new FileInputStream(new File(root,path));
    }

    private static boolean saveBaseline(Context c,Uri tree,Map<String,Entry> snapshot)throws Exception{
        android.content.SharedPreferences prefs=c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        JSONArray items=new JSONArray(prefs.getString(KEY_PROJECTS,"[]"));
        JSONObject baseline=new JSONObject();
        for(Entry e:snapshot.values()){
            JSONObject v=new JSONObject();v.put("dir",e.directory);if(!e.directory)v.put("hash",e.hash);
            baseline.put(e.path,v);
        }
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item!=null&&tree.toString().equals(item.optString("uri"))){
                item.put(KEY_BASELINE,baseline);
                return prefs.edit().putString(KEY_PROJECTS,items.toString()).commit();
            }
        }
        return false;
    }

    private static Map<String,Entry> decodeBaseline(JSONObject json)throws Exception{
        Map<String,Entry> out=new HashMap<String,Entry>();
        java.util.Iterator<String> it=json.keys();
        while(it.hasNext()){
            String p=it.next();JSONObject v=json.getJSONObject(p);boolean dir=v.optBoolean("dir",false);
            out.put(p,new Entry(p,dir,dir?"":v.optString("hash",""),null,dir?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream"));
        }
        return out;
    }

    private static void sourcesFrom(Map<String,Entry> desired,Map<String,String> sources,String source){
        for(Entry e:desired.values())sources.put(e.path,source);
    }

    private static boolean sameSnapshot(Map<String,Entry>a,Map<String,Entry>b){
        if(!a.keySet().equals(b.keySet()))return false;
        for(String p:a.keySet())if(!sameState(a.get(p),b.get(p)))return false;
        return true;
    }

    private static boolean sameState(Entry a,Entry b){
        if(a==null||b==null)return a==b;
        return a.directory==b.directory&&(a.directory||a.hash.equals(b.hash));
    }

    private static Entry scanEntryLocal(File f,String path)throws Exception{
        if(!f.exists())return null;
        return f.isDirectory()?new Entry(path,true,"",null,DocumentsContract.Document.MIME_TYPE_DIR)
            :new Entry(path,false,hashFile(f),null,"application/octet-stream");
    }

    private static void collectLocalPaths(File root,File dir,String prefix,ArrayList<String> out)throws Exception{
        File[] kids=dir.listFiles();if(kids==null)throw new IOException("Nao consegui listar a copia local.");
        for(File f:kids){String p=prefix.isEmpty()?f.getName():prefix+"/"+f.getName();out.add(p);if(f.isDirectory())collectLocalPaths(root,f,p,out);}
    }

    private static String hashFile(File f)throws Exception{try(InputStream in=new FileInputStream(f)){return hashStream(in);}}
    private static String hashStream(InputStream in)throws Exception{
        if(in==null)throw new IOException("Nao consegui ler um arquivo para comparar.");
        try(InputStream input=in){
            MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=new byte[32768];int n;
            while((n=input.read(b))!=-1)md.update(b,0,n);
            StringBuilder s=new StringBuilder();for(byte x:md.digest())s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return s.toString();
        }
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    private static int depth(String p){int n=1;for(int i=0;i<p.length();i++)if(p.charAt(i)=='/')n++;return n;}
    private static boolean safeName(String n){return n!=null&&!n.trim().isEmpty()&&!n.equals(".")&&!n.equals("..")&&n.indexOf('/')<0&&n.indexOf('\\')<0;}
    private static void ensureInside(File root,File target)throws IOException{if(!root.getCanonicalFile().equals(target.getCanonicalFile().getParentFile())&&!target.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new IOException("Caminho local invalido: "+target);}
    private static boolean isSymlink(File f){try{return !f.getCanonicalPath().equals(new File(f.getParentFile().getCanonicalFile(),f.getName()).getPath());}catch(IOException e){return true;}}
}
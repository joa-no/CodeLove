/*
 * CodeLove - operacoes com arquivos e pastas dos projetos.
 * Nao depende do Android, so de Java comum.
 */

package org.love2d.android;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class FileOps {
    private static final int MAX_NAME = 64;

    static final String NEW_PROJECT_MAIN =
        "-- Projeto novo do CodeLove.\n"
        + "-- Escreva o seu jogo aqui.\n"
        + "\n"
        + "function love.load()\n"
        + "end\n"
        + "\n"
        + "function love.update(dt)\n"
        + "end\n"
        + "\n"
        + "function love.draw()\n"
        + "end\n";

    private FileOps() {
    }

    /** Devolve a mensagem de erro se o nome nao servir, ou nulo se estiver tudo certo. */
    static String checkName(File folder, String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Digite um nome.";
        }
        if (name.length() > MAX_NAME) {
            return "Nome muito longo (maximo " + MAX_NAME + " letras).";
        }
        if (name.equals(".") || name.equals("..")) {
            return "Esse nome nao e permitido.";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "/\\:*?\"<>|".indexOf(c) >= 0) {
                return "O nome nao pode ter / \\ : * ? \" < > |";
            }
        }
        if (name.endsWith(".") || name.endsWith(" ")) {
            return "O nome nao pode terminar com ponto ou espaco.";
        }
        if (name.endsWith(".tmp")) {
            return "Nomes terminados em .tmp sao reservados.";
        }
        if (new File(folder, name).exists()) {
            return "Ja existe um item com esse nome.";
        }
        return null;
    }

    /** Arquivo sem extensao vira .lua (o caso mais comum no CodeLove). */
    static String withDefaultExtension(String name) {
        return name.indexOf('.') >= 0 ? name : name + ".lua";
    }

    /** Cria a pasta do projeto com um main.lua inicial. Devolve nulo se falhar. */
    static File createProject(File gamesDir, String name) {
        File project = new File(gamesDir, name);
        if (!project.mkdirs()) {
            return null;
        }
        if (!writeText(new File(project, "main.lua"), NEW_PROJECT_MAIN)) {
            return null;
        }
        return project;
    }

    /** Cria um arquivo vazio. Devolve nulo se falhar. */
    static File createFile(File folder, String name) {
        File file = new File(folder, name);
        try {
            return file.createNewFile() ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Cria uma pasta. Devolve nulo se falhar. */
    static File createFolder(File parent, String name) {
        File folder = new File(parent, name);
        return folder.mkdir() ? folder : null;
    }

    /** Apaga o arquivo, ou a pasta com tudo dentro. Devolve true se apagou tudo. */
    static boolean deleteRecursive(File target) {
        boolean ok = true;
        if (target.isDirectory() && !isSymlink(target)) {
            File[] kids = target.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    ok &= deleteRecursive(kid);
                }
            }
        }
        return target.delete() && ok;
    }

    private static boolean isSymlink(File file) {
        try {
            return !file.getCanonicalPath().equals(
                new File(file.getParentFile().getCanonicalFile(), file.getName()).getPath());
        } catch (IOException e) {
            return true; // na duvida, nao entra na pasta
        }
    }

    /**
     * Na primeira vez que o app roda, cria o projeto de exemplo. Se voce apagar o
     * exemplo depois, ele NAO volta (a marca fica ao lado da pasta de projetos).
     */
    static void ensureSampleProject(File gamesDir) {
        if (gamesDir == null || !gamesDir.isDirectory()) {
            return;
        }
        File marker = new File(gamesDir.getParentFile(), "codelove_sample_created");
        if (marker.exists()) {
            return;
        }

        String[] existing = gamesDir.list();
        if (existing == null || existing.length == 0) {
            File project = new File(gamesDir, "meu_jogo");
            writeText(new File(project, "main.lua"),
                "-- Projeto de exemplo do CodeLove.\n"
                + "-- Edite este arquivo para mudar o jogo.\n"
                + "\n"
                + "local x = 100\n"
                + "\n"
                + "function love.update(dt)\n"
                + "  x = x + 60 * dt\n"
                + "end\n"
                + "\n"
                + "function love.draw()\n"
                + "  love.graphics.print(\"Ola, CodeLove!\", x, 100)\n"
                + "end\n");
            writeText(new File(project, "conf.lua"),
                "function love.conf(t)\n"
                + "  t.window.title = \"meu_jogo\"\n"
                + "end\n");
            writeText(new File(new File(project, "scripts"), "util.lua"),
                "local util = {}\n"
                + "\n"
                + "function util.clamp(v, a, b)\n"
                + "  return math.max(a, math.min(b, v))\n"
                + "end\n"
                + "\n"
                + "return util\n");
        }

        writeText(marker, "1");
    }

    static boolean writeText(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

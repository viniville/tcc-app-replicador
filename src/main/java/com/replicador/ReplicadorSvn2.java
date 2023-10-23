package com.replicador;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.tmatesoft.svn.core.SVNCommitInfo;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNOptions;
import org.tmatesoft.svn.core.io.ISVNEditor;
import org.tmatesoft.svn.core.io.SVNRepository;
import org.tmatesoft.svn.core.io.SVNRepositoryFactory;
import org.tmatesoft.svn.core.io.diff.SVNDeltaGenerator;
import org.tmatesoft.svn.core.wc.*;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class ReplicadorSvn2 {

    public static final String BRANCH_PROCESS = "_branch-process";
    private static Repository repoOriginal;
    private static SVNURL reposURL;
    private static SVNRepository repoCopy;
    private static File wcRoot;
    private static StringBuilder sbFinal = new StringBuilder();
    private static List<String> arquivosModificados;

    public static void run(File dirGit, File dirBaseReplica) throws Exception {
        final Instant start = Instant.now();
        repoOriginal = null;
        reposURL = null;
        repoCopy = null;
        wcRoot = null;
        sbFinal = new StringBuilder();
        arquivosModificados = new ArrayList<>();

        //Abrir o reposit�rio em quest�o
        openRepository(dirGit);

        //Inicializa repositorio a ser replicado
        initRepository(dirBaseReplica.getAbsolutePath() + File.separator + dirGit.getName());

        //Rebuild do repositorio
        System.out.println("********************************************************");
        System.out.println("INICIO PROCESSO SVN - Repositorio: " + dirGit.getName());
        System.out.println("********************************************************");
        rebuildRepository();
        Duration tempoExecucao = Duration.between(start, Instant.now());
        System.out.println("********************************************************");
        System.out.println("FIM PROCESSO SVN - Repositorio: " + dirGit.getName());
        System.out.println("********************************************************");
        try (PrintWriter out = new PrintWriter(dirBaseReplica.getAbsolutePath() + File.separator
                + dirGit.getName() + ".txt")) {
            out.println(sbFinal.toString());
            out.println(String.format("Duração: %s (em segundos); %s (em minutos)%n", tempoExecucao.getSeconds(),
                    tempoExecucao.getSeconds() / 60));
        }
    }

    public static void openRepository(File dir) throws IOException {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        repoOriginal = builder
                .setGitDir(new File(dir.getAbsolutePath() + "/.git"))
                .readEnvironment() // scan
                // environment
                // GIT_*d
                // variables
                .findGitDir() // scan up the file system tree
                .build();
    }

    private static void initRepository(String diretorio) throws SVNException {

        File dir = new File(diretorio);
        deleteDirectory(dir);
        dir.mkdirs();

        // Inicializa as variaveis
        SamplesUtility.initializeFSFSprotocol();

        File dirRepoCopy = new File(diretorio);

        // cria o repositorio
        SamplesUtility.createRepository(dirRepoCopy);

        // cria a URL do repositorio principal
        reposURL = SVNURL.fromFile(dirRepoCopy);

        // Obtem a instancia SVNRepository
        repoCopy = SVNRepositoryFactory.create(reposURL);

        // Efetua um commit inicial
        System.out.println(commitInicial());

        String repoBranchProcess = diretorio.endsWith("/") ? diretorio.substring(1, diretorio.length() - 1) : diretorio;
        repoBranchProcess += BRANCH_PROCESS;
        wcRoot = new File(repoBranchProcess);

        deleteDirectory(wcRoot);

        // 1) Faz checkout do servidor
        if (!wcRoot.exists())
            SamplesUtility.checkOutWorkingCopy(reposURL, wcRoot);

    }

    private static SVNCommitInfo commitInicial() throws SVNException {
        ISVNEditor commitEditor = repoCopy.getCommitEditor(
                "initializing the repository with a greek tree", null, false,
                null, null);

        SVNDeltaGenerator deltaGenerator = new SVNDeltaGenerator();

        commitEditor.openRoot(SVNRepository.INVALID_REVISION);

        // add /iota file
        commitEditor.addFile("iota", null, SVNRepository.INVALID_REVISION);
        commitEditor.applyTextDelta("iota", null);
        String fileText = "Commit inicial";
        String checksum = deltaGenerator.sendDelta("iota",
                new ByteArrayInputStream(fileText.getBytes()), commitEditor,
                true);
        commitEditor.closeFile("iota", checksum);

        commitEditor.closeDir();

        return commitEditor.closeEdit();
    }

    private static void rebuildRepository() throws Exception {
        Ref head = repoOriginal.getRefDatabase().findRef("HEAD");
        RevWalk walk = new RevWalk(repoOriginal);
        walk.sort(RevSort.REVERSE, true);
        RevCommit commit = walk.parseCommit(head.getObjectId());
        walk.markStart(commit);

        RevCommit rev;
        int i = 0;
        //Iterar sobre os commits do reposit�rio (master)
        while ((rev = walk.next()) != null) {
            if (i++ == 0)
                continue;

            //Para cada commit verificar se tem mais que um pai/parent
            //Quando tiver mais que 1, significa que é um arquivo mergeado
            if (rev.getParentCount() > 1) {
                var idParents = Arrays.stream(rev.getParents())
                        .map(AnyObjectId::getName)
                        .collect(Collectors.joining(","));
                System.out.printf("ID: %s; ParentCount: %s; Parents IDs: [%s]; Author: %s%n", rev.getName(),
                        rev.getParentCount(), idParents, rev.getAuthorIdent());

                int countBranches = 0;

                // 7) Buscar os arquivos fontes de cada um dos commits pais
                RevCommit[] parents = rev.getParents();
                for (RevCommit revCommit : parents) {

                    ObjectId id = repoOriginal.resolve(revCommit.getName());
                    RevCommit revCommitComplete = walk.parseCommit(id);

                    //Buscar arquivos fontes da revisao
                    Map<String, String> arquivosFontes = buscaArquivosFontes(
                            revCommitComplete, repoOriginal);

                    var strFontes = String.join(",", arquivosFontes.keySet());
                    System.out.printf("Arquivos do commit %s; Parent Id %s : [%s]%n",
                            rev.getName(), revCommit.getName(), strFontes);

                    //Comitar no seu respectivo repositorio
                    commitaArquivosSvn(arquivosFontes, countBranches++ == 0);
                }
                //Efetuar o merge da copia de trabalho no tronco
                mergeWcs();

                //Obter a revisao do merge efetuado
                long revision1 = repoCopy.getLatestRevision();

                //Obter e comitar arquivos originais resultantes dos merges
                Map<String, String> arquivosMergeResultante = buscaArquivosFontes(
                        rev, repoOriginal);

                commitaArquivosSvn(arquivosMergeResultante, true);

                //Obter a revisao
                long revision2 = repoCopy.getLatestRevision();

                //Comparar duas revisoes obtidas anteriormente
                comparaRevisoes(revision1, revision2, arquivosMergeResultante);
            }
        }
    }

    private static Map<String, String> buscaArquivosFontes(RevCommit rev, Repository repoOriginal) throws IOException {

        Map<String, String> mapRetorno = new HashMap<>(0);

        RevWalk rw = new RevWalk(repoOriginal);

        if (rev.getParentCount() == 0)
            return mapRetorno;

        RevCommit parent = rw.parseCommit(rev.getParent(0).getId());
        DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
        df.setRepository(repoOriginal);
        df.setDiffComparator(RawTextComparator.DEFAULT);
        df.setDetectRenames(true);
        List<DiffEntry> diffs = df.scan(parent.getTree(), rev.getTree());
        for (DiffEntry diff : diffs) {
            if (!validExtensionFiles(diff.getNewPath()))
                continue;
            try {
                RevTree tree = rev.getTree();
                TreeWalk treeWalk = new TreeWalk(repoOriginal);
                treeWalk.addTree(tree);
                treeWalk.setRecursive(true);
                treeWalk.setFilter(PathFilter.create(diff.getNewPath()));
                if (!treeWalk.next()) {
                    mapRetorno.put(diff.getNewPath(), "");
                    continue;
                }

                ObjectId objectId = treeWalk.getObjectId(0);
                ObjectLoader loader = repoOriginal.open(objectId);

                String source = new String(loader.getBytes());
                mapRetorno.put(diff.getNewPath(), source);
            } catch (Exception e) {
                System.out.println("Erro:" + e.getMessage());
            }
        }

        return mapRetorno;
    }

    private static boolean validExtensionFiles(String filename) {
        var invalidExtensions = List.of("jar", "zip", "7zip", "rar", "tar", "svg", "png", "jpg", "exe", "msi",
                "ico", "bmp", "so", "ttf", "xls", "xlsx", "doc", "docx", "ppt", "pptx", "pdf");
        if (filename != null && !filename.isBlank()) {
            int dotIdx = filename.lastIndexOf(".");
            if (dotIdx > 0) {
                final var extFile = filename.substring(dotIdx + 1).toLowerCase();
                return !invalidExtensions.contains(extFile);
            }
        }
        return true;
    }

    public static boolean deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            assert children != null;
            for (String child : children) {
                boolean success = deleteDirectory(new File(dir, child));
                if (!success) {
                    return false;
                }
            }
        }

        return dir.delete();
    }

    public static int count(String arquivo, String path, String caracter) {
        int count = 0;
        String[] split = arquivo.split("Index: ");
        for (String string : split) {
            if (!string.contains(BRANCH_PROCESS + "/" + path))
                continue;

            String[] arrayTeste = string.split("\n");
            for (String string2 : arrayTeste) {
                if (string2.startsWith(caracter + caracter + caracter))
                    continue;

                if (string2.startsWith(caracter))
                    count++;
            }
        }
        return count;
    }

    /**
     * Este metodo devera comitar os arquivos da map no branche informado
     *
     * @param arquivosFontes
     * @throws Exception
     */
    private static void commitaArquivosSvn(Map<String, String> arquivosFontes, boolean commit) throws Exception {

        if (arquivosFontes == null || arquivosFontes.isEmpty())
            return;

        SVNClientManager clientManager = SVNClientManager.newInstance();
        for (Entry<String, String> regs : arquivosFontes.entrySet()) {
            if (regs.getKey().equals("/dev/null") && (regs.getValue() == null || regs.getValue().isBlank()))
                continue;

            File file = new File(wcRoot, regs.getKey());
            if (file.isDirectory()) {
                continue;
            }
            if (file.getParentFile().exists() && !file.getParentFile().isDirectory()) {
                System.out.printf("ATENÇÃO! Identificado aquivo com mesmo nome do diretorio parent " +
                                "que deve ser criado. Arquivo: %s.\nTentando deletar arquivo -> ",
                        file.getParentFile());
                System.out.println(file.getParentFile().delete() ? "Deletado com sucesso!" : "Falha ao remover!");
            }
            if (file.isFile()) {
                file.getParentFile().mkdirs();
            }
            if (file.exists() && !file.isFile()) {
                System.out.printf("ATENÇÃO! Identificado diretorio com mesmo nome do arquivo " +
                        "que deve ser criado. Diretorio: %s.\nTentando remover diretorio -> ", file);
                System.out.println(file.delete() ? "Removido com sucesso!" : "Falha ao remover!");
            }
            SamplesUtility.writeToFile(file, regs.getValue(), false);

            clientManager.getWCClient().doAdd(file, true, false, true, true);
        }

        if (commit) {
            SVNCommitClient commitClient = clientManager.getCommitClient();
            commitClient.doCommit(new File[]{wcRoot}, false,
                    "committing changes", null, null, false, false,
                    SVNDepth.INFINITY);
        }
    }

    /**
     * Efetua o merge entre branches
     *
     * @throws SVNException
     */
    private static void mergeWcs() throws SVNException {

        SVNClientManager clientManager = SVNClientManager.newInstance();
        SVNCommitClient commitClient = clientManager.getCommitClient();

        // agora compare a revisão base da cópia de trabalho com o repositório
        SVNDiffClient diffClient = clientManager.getDiffClient();

        /*
         * Como não fornecemos nenhuma implementação personalizada de ISVNOptions para SVNClientManager,
         * nosso gerenciador usa DefaultSVNOptions, que é definido para todas as classes de cliente SVN
         * que o gerenciador produz. Então, podemos lançar ISVNOptions para DefaultSVNOptions.
         */
        DefaultSVNOptions options = (DefaultSVNOptions) diffClient.getOptions();
        //Desta forma, definimos um manipulador de conflitos que resolverá automaticamente
        // os conflitos para os casos que gostaríamos
        options.setConflictHandler(new ConflictResolverHandler());
        /*
         * faça a mesma chamada de mesclagem, o recurso de rastreamento de mesclagem
         * mesclará apenas as revisões que ainda não foram mescladas.
         */
        SVNUpdateClient updateClient = clientManager.getUpdateClient();
        updateClient.doUpdate(wcRoot,
                SVNRevision.create(repoCopy.getLatestRevision()),
                SVNDepth.INFINITY, true, true);

        SVNRevisionRange rangeToMerge = new SVNRevisionRange(
                SVNRevision.create(0), SVNRevision.HEAD);

        diffClient.doMerge(reposURL, SVNRevision.HEAD,
                Collections.singleton(rangeToMerge), wcRoot, SVNDepth.UNKNOWN,
                true, false, false, false);

        //Comita-lo
        commitClient.doCommit(new File[]{wcRoot}, false,
                "committing changes", null, null, false, false,
                SVNDepth.INFINITY);
    }

    /**
     * Efetua comparativo entre versão mergeada automaticamente e versão
     * resultado do merge
     *
     * @param revision1
     * @param revision2
     */
    private static void comparaRevisoes(long revision1, long revision2, Map<String, String> fontes) {
        SVNClientManager clientManager = SVNClientManager.newInstance();
        //agora compara a revisão base da cópia de trabalho com o repositório
        SVNDiffClient diffClient = clientManager.getDiffClient();
        try {
            // 1) dentro do metodo doDiffStatus, salvar arquivos modificados
            // 2) no no metodo doDiff, devemos obter a string da diferenca e
            // procurar arquivos, obtendo as linhas adicionadas e removidas
            diffClient.doDiffStatus(reposURL, SVNRevision.create(revision1),
                    reposURL, SVNRevision.create(revision2), SVNDepth.INFINITY,
                    true, new ISVNDiffStatusHandler() {
                        @Override
                        public void handleDiffStatus(SVNDiffStatus diffStatus) {
                            if (SVNStatusType.STATUS_MODIFIED.equals(diffStatus.getModificationType())) {
                                arquivosModificados.add(diffStatus.getPath());
                            }
                        }
                    });

            if (arquivosModificados.isEmpty())
                return;

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                //Corresponde a 'svn diff -rBASE:HEAD'.
                diffClient.doDiff(wcRoot, SVNRevision.UNDEFINED,
                        SVNRevision.create(revision1), SVNRevision.create(revision2),
                        SVNDepth.INFINITY, true, output, null);

                StringBuilder sb = new StringBuilder();
                try (ByteArrayInputStream bais = new ByteArrayInputStream(output.toByteArray());
                     BufferedReader bfr = new BufferedReader(new InputStreamReader(bais))) {
                    String line;
                    while ((line = bfr.readLine()) != null)
                        sb.append(line).append(System.lineSeparator());
                }

                for (String path : arquivosModificados) {
                    // para cada arq modificado buscar as linhas totais (buscar do arquivo original fazer como no git)
                    String linhasArquivoResultante = fontes.get(path);
                    String[] qtdLinhas = linhasArquivoResultante.split("\n");
                    sbFinal.append(path)
                            .append(";")
                            .append(qtdLinhas.length)
                            .append(";")
                            .append(count(sb.toString(), path, "+"))
                            .append(";")
                            .append(count(sb.toString(), path, "-"))
                            .append("\n");
                }
            }
            arquivosModificados.clear();
        } catch (Exception e) {
            System.out.println("Erro ao efetuar comparar revisões. " + e.getMessage());
        }
    }

    private static class ConflictResolverHandler implements ISVNConflictHandler {
        public SVNConflictResult handleConflict(SVNConflictDescription conflictDescription) {
            SVNMergeFileSet mergeFiles = conflictDescription.getMergeFiles();
            System.out.println("Automatically resolving conflict for "
                    + mergeFiles.getWCFile() + ", choosing local conflicts");
            return new SVNConflictResult(SVNConflictChoice.MINE_CONFLICT,
                    mergeFiles.getResultFile());
        }
    }
}

#! /bin/sh

# Parâmetros:
#  - 1: diretorio onde esta repositorio copia
#  - 2: mensagem de commit

if [ "$1" = "" ] && [ "$2" = "" ]; then
	echo "Parâmetros não informados!"
	echo "Valores esperados: {DIRETORIO REPO COPY} {MENSAGEM COMMIT}"
	exit 1
fi

# Acessando o diretório base
#cd /home/penguin/repositorios/teste
cd $1

# efetua checkout para o master
git commit -a -m "$2"


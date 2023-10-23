#! /bin/sh

# Par�metros:
#  - 1: diretorio onde esta repositorio copia
#  - 2: nome do branche a fazer merge

if [ "$1" = "" ] && [ "$2" = "" ] && [ "$3" = "" ]; then
	echo "Par�metros n�o informados!"
	echo "Valores esperados: {DIRETORIO REPO COPY} {NOME BRANCHE}"
	exit 1
fi

# Acessando o diret�rio base
#cd /home/penguin/repositorios/teste
cd $1

# efetua checkout para o master
git checkout $2

# Remove diret�rios e arquivos iniciados com SW
#git merge -s recursive -Xours style
git merge -s recursive -Xours $3

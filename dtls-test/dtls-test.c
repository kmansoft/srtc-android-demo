#include <sys/types.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdio.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

#include <openssl/ssl.h>
#include <openssl/bio.h>
#include <openssl/err.h>
#include <openssl/rand.h>
#include <openssl/opensslv.h>
#include <openssl/rsa.h>

#define BUFFER_SIZE          (1<<16)
#define COOKIE_SECRET_LENGTH 16

int verbose = 1;
int veryverbose = 1;

void start_client(char *remote_address, char *local_address, int port) {
	int fd, retval;
	union {
		struct sockaddr_storage ss;
		struct sockaddr_in s4;
		struct sockaddr_in6 s6;
	} remote_addr, local_addr;
	char buf[BUFFER_SIZE];
	char addrbuf[INET6_ADDRSTRLEN];
	socklen_t len;
	SSL_CTX *ctx;
	SSL *ssl;
	BIO *bio;
	int reading = 0;
	struct timeval timeout;
#if WIN32
	WSADATA wsaData;
#endif

	memset((void *) &remote_addr, 0, sizeof(struct sockaddr_storage));
	memset((void *) &local_addr, 0, sizeof(struct sockaddr_storage));

	if (inet_pton(AF_INET, remote_address, &remote_addr.s4.sin_addr) == 1) {
		remote_addr.s4.sin_family = AF_INET;
#ifdef HAVE_SIN_LEN
		remote_addr.s4.sin_len = sizeof(struct sockaddr_in);
#endif
		remote_addr.s4.sin_port = htons(port);
	} else if (inet_pton(AF_INET6, remote_address, &remote_addr.s6.sin6_addr) == 1) {
		remote_addr.s6.sin6_family = AF_INET6;
#ifdef HAVE_SIN6_LEN
		remote_addr.s6.sin6_len = sizeof(struct sockaddr_in6);
#endif
		remote_addr.s6.sin6_port = htons(port);
	} else {
		return;
	}

	printf("Parsed remote addr\n");

#ifdef WIN32
	WSAStartup(MAKEWORD(2, 2), &wsaData);
#endif

	fd = socket(remote_addr.ss.ss_family, SOCK_DGRAM, 0);
	if (fd < 0) {
		perror("socket");
		exit(-1);
	}

	printf("Created socket\n");

	if (local_address && strlen(local_address) > 0) {
		if (inet_pton(AF_INET, local_address, &local_addr.s4.sin_addr) == 1) {
			local_addr.s4.sin_family = AF_INET;
#ifdef HAVE_SIN_LEN
			local_addr.s4.sin_len = sizeof(struct sockaddr_in);
#endif
			local_addr.s4.sin_port = htons(0);
		} else if (inet_pton(AF_INET6, local_address, &local_addr.s6.sin6_addr) == 1) {
			local_addr.s6.sin6_family = AF_INET6;
#ifdef HAVE_SIN6_LEN
			local_addr.s6.sin6_len = sizeof(struct sockaddr_in6);
#endif
			local_addr.s6.sin6_port = htons(0);
		} else {
			return;
		}
		OPENSSL_assert(remote_addr.ss.ss_family == local_addr.ss.ss_family);
		if (local_addr.ss.ss_family == AF_INET) {
			if (bind(fd, (const struct sockaddr *) &local_addr, sizeof(struct sockaddr_in))) {
				perror("bind");
				exit(EXIT_FAILURE);
			}
		} else {
			if (bind(fd, (const struct sockaddr *) &local_addr, sizeof(struct sockaddr_in6))) {
				perror("bind");
				exit(EXIT_FAILURE);
			}
		}
	}

	OpenSSL_add_ssl_algorithms();
	OPENSSL_init_ssl(0, NULL);
	SSL_load_error_strings();

	ctx = SSL_CTX_new(DTLS_client_method());
	//SSL_CTX_set_cipher_list(ctx, "eNULL:!MD5");

	SSL_CTX_set_min_proto_version(ctx, DTLS1_VERSION);

	// kostya begin
    EVP_PKEY* pkey = EVP_PKEY_new();

    RSA* rsa = RSA_generate_key(2048, RSA_F4, NULL, NULL);
    if (!rsa) {
    	printf("ERROR: can't generate the RSA key\n");
    	return;
    }
    EVP_PKEY_assign_RSA(pkey, rsa);

    X509* x509 = X509_new();

    ASN1_INTEGER_set(X509_get_serialNumber(x509), 1);
    X509_gmtime_adj(X509_get_notBefore(x509), 0);
    X509_gmtime_adj(X509_get_notAfter(x509), 31536000L);

    X509_set_pubkey(x509, pkey);

    X509_NAME* name = X509_get_subject_name(x509);

    X509_NAME_add_entry_by_txt(name, "C",  MBSTRING_ASC,
                               (unsigned char *)"US", -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "O",  MBSTRING_ASC,
                               (unsigned char *)"MyCompany Inc.", -1, -1, 0);
    X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                               (unsigned char *)"localhost", -1, -1, 0);

    X509_set_issuer_name(x509, name);

    if (!X509_sign(x509, pkey, EVP_sha1())) {
    	printf("ERROR: can't sign the cert!\n");
		return;
    }
	// kostya end

	if (!SSL_CTX_use_certificate(ctx, x509)) {
		printf("ERROR: no certificate found!\n");
		return;
	}

	if (!SSL_CTX_use_PrivateKey(ctx, pkey)) {
		printf("ERROR: no private key found!\n");
		return;
	}

	if (!SSL_CTX_check_private_key (ctx)) {
		printf("ERROR: invalid private key!\n");
		return;
	}

	SSL_CTX_set_verify_depth (ctx, 2);
	SSL_CTX_set_read_ahead(ctx, 1);

	ssl = SSL_new(ctx);

	SSL_set_tlsext_use_srtp(
		        ssl, "SRTP_AEAD_AES_256_GCM:SRTP_AEAD_AES_128_GCM:SRTP_AES128_CM_SHA1_80");

	printf("Created ssl\n");

	/* Create BIO, connect and set to already connected */
	bio = BIO_new_dgram(fd, BIO_CLOSE);
	if (remote_addr.ss.ss_family == AF_INET) {
		if (connect(fd, (struct sockaddr *) &remote_addr, sizeof(struct sockaddr_in))) {
			perror("connect");
		}
	} else {
		if (connect(fd, (struct sockaddr *) &remote_addr, sizeof(struct sockaddr_in6))) {
			perror("connect");
		}
	}
	BIO_ctrl(bio, BIO_CTRL_DGRAM_SET_CONNECTED, 0, &remote_addr.ss);

	SSL_set_bio(ssl, bio, bio);

	printf("Connecting...\n");

	retval = SSL_do_handshake(ssl);
	if (retval <= 0) {
		switch (SSL_get_error(ssl, retval)) {
			case SSL_ERROR_ZERO_RETURN:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_ZERO_RETURN\n");
				break;
			case SSL_ERROR_WANT_READ:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_WANT_READ\n");
				break;
			case SSL_ERROR_WANT_WRITE:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_WANT_WRITE\n");
				break;
			case SSL_ERROR_WANT_CONNECT:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_WANT_CONNECT\n");
				break;
			case SSL_ERROR_WANT_ACCEPT:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_WANT_ACCEPT\n");
				break;
			case SSL_ERROR_WANT_X509_LOOKUP:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_WANT_X509_LOOKUP\n");
				break;
			case SSL_ERROR_SYSCALL:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_SYSCALL\n");
				break;
			case SSL_ERROR_SSL:
				fprintf(stderr, "SSL_connect failed with SSL_ERROR_SSL\n");
				break;
			default:
				fprintf(stderr, "SSL_connect failed with unknown error\n");
				break;
		}
		exit(EXIT_FAILURE);
	}

	/* Set and activate timeouts */
	timeout.tv_sec = 3;
	timeout.tv_usec = 0;
	BIO_ctrl(bio, BIO_CTRL_DGRAM_SET_RECV_TIMEOUT, 0, &timeout);

	if (verbose) {
		if (remote_addr.ss.ss_family == AF_INET) {
			printf ("\nConnected to %s\n",
					 inet_ntop(AF_INET, &remote_addr.s4.sin_addr, addrbuf, INET6_ADDRSTRLEN));
		} else {
			printf ("\nConnected to %s\n",
					 inet_ntop(AF_INET6, &remote_addr.s6.sin6_addr, addrbuf, INET6_ADDRSTRLEN));
		}
	}

	if (veryverbose && SSL_get_peer_certificate(ssl)) {
		printf ("------------------------------------------------------------\n");
		X509_NAME_print_ex_fp(stdout, X509_get_subject_name(SSL_get_peer_certificate(ssl)),
							  1, XN_FLAG_MULTILINE);
		printf("\n\n Cipher: %s", SSL_CIPHER_get_name(SSL_get_current_cipher(ssl)));
		printf ("\n------------------------------------------------------------\n\n");
	}

	#ifdef WIN32
	closesocket(fd);
#else
	close(fd);
#endif
	if (verbose)
		printf("Connection closed.\n");

#ifdef WIN32
	WSACleanup();
#endif
}


int main(int argc, char **argv)
{
	argc--;
	argv++;

	if (argc != 2) {
		printf("Usage: dtls-test remote_addr remote_port\n");
		return 1;
	}

	int port = atoi(argv[1]);
	if (port <= 0) {
		printf("Invalid port number\n");
		return 1;
	}

	printf("Connecting to %s port %d\n", argv[0], port);

	start_client(argv[0], NULL, port);

	return 0;
}
CREATE TABLE AGREEMENT( AGREE_ID BIGINT NOT NULL,
  VER BIGINT NOT NULL,
  CLIENT_ID BIGINT NOT NULL,
  AGREE_CD VARCHAR(200),
  DESCR VARCHAR(200),
  EFF_DT DATE,
  EXPR_DT DATE,
  VLD_FRM_DT TIMESTAMP NOT NULL,
  VLD_TO_DT TIMESTAMP,
  SRC_SYS_REF_ID VARCHAR(200) NOT NULL,
  SRC_SYS_REC_ID VARCHAR(200)) ;

CREATE TABLE BANK(
 BNK_ORG_ID BIGINT NOT NULL,
 BNK_ID BIGINT NOT NULL,
 VER BIGINT NOT NULL,
 CLIENT_ID BIGINT NOT NULL,
 BNK_FULL_NM VARCHAR(50),
 RTNG_NUM VARCHAR(35) NOT NULL,
 VLD_FRM_DT TIMESTAMP NOT NULL,
 VLD_TO_DT TIMESTAMP,
 SRC_SYS_REF_ID VARCHAR(10) NOT NULL,
 SRC_SYS_REC_ID VARCHAR(150)) USING column OPTIONS(partition_by 'BNK_ORG_ID', buckets '32',key_columns 'CLIENT_ID,BNK_ORG_ID,BNK_ID ' ) ;

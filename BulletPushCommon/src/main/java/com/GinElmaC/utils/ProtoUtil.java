package com.GinElmaC.utils;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Proto工具类
 */
public class ProtoUtil {
    //AES加密方法要求密钥长度为128位(16字节)
    private static final int IV_LENGTH = 16;
    //初始化Cipher的参数，分别是 加密方法/模式/填充
    private static final String AES_ALGORITHNM = "AES/CBC/PKCS5Padding";

    /**
     * AES解密
     * @param data 待解密数据
     * @param key 加密密钥
     * @return
     */
    public static byte[] deAES(String data,byte[] key){
        try {
            //SecretKeySpec对象用于进行对称加密，该对象允许直接使用一个字节数组创造一个合法的SecretKeySpec对象
            SecretKeySpec keySpec = validateKey(key);
            byte[] decode = Base64.getDecoder().decode(data);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decode,0,iv,0,iv.length);

            /**
             * Cipher是java的加密核心类，可以用来对称加密和解密
             */
            Cipher cipher = Cipher.getInstance(AES_ALGORITHNM);
            //初始化加密解密组件，方法签名：操作模式、SecretKeySpec、IV。IV是一个向量，作用是确保每次加密后的结果不同，同时加密以及解密的时候要求使用同一个IV
            cipher.init(Cipher.DECRYPT_MODE,keySpec,new IvParameterSpec(iv));
            //doFinal方法是加解密的具体方法，方法签名：待解密数组、起始位置、要求解密的长度
            return cipher.doFinal(decode,iv.length,decode.length-iv.length);
        } catch (Exception e) {
            throw new SecurityException("AES解密失败",e);
        }
    }
    //密钥初始化，用于自动处理不同长度的密钥128/192/256
    private static SecretKeySpec validateKey(byte[] key){
        byte[] validKey = new byte[32]; //默认256位
        /**
         * System.arraycopy用于高效复制数组
         * 方法签名：原数组，原数组起始位置，目标数组，目标数组起始位置，复制个数
         */
        System.arraycopy(key,0,validKey,0,Math.min(key.length,validKey.length));
        return new SecretKeySpec(validKey,"AES");
    }

    /**
     * g-zip解压缩
     */
    public static byte[] decompress(byte[] compressedDate){
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(compressedDate);
            GZIPInputStream gzip = new GZIPInputStream(bis);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();

            byte[] buffer = new byte[1024];
            int len;
            while((len = gzip.read(buffer))>0){
                bos.write(buffer,0,len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalArgumentException("gzip解压失败",e);
        }
    }

    /**
     * 压缩
     * @param dataBytes
     * @return
     */
    public static byte[] compress(byte[] dataBytes) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gzip = new GZIPOutputStream(bos);
            gzip.write(dataBytes);
            gzip.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("gzip压缩失败");
        }
    }

    /**
     * 加密
     * @param dataBytes
     * @return
     */
    public static String doAES(byte[] dataBytes,byte[] key) {
        try {
            SecretKeySpec speckey = validateKey(key);
            byte[] iv = generateIV();

            Cipher cipher = Cipher.getInstance(AES_ALGORITHNM);
            cipher.init(Cipher.ENCRYPT_MODE,speckey,new IvParameterSpec(iv));

            byte[] encryted = cipher.doFinal(dataBytes);
            byte[] combined = new byte[iv.length + encryted.length];
            System.arraycopy(iv,0,combined,0,iv.length);
            System.arraycopy(encryted,0,combined,iv.length,encryted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] generateIV(){
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        return iv;
    }

}

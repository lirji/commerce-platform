package com.lrj.commerce.catalog.infrastructure;
import com.lrj.commerce.catalog.api.CatalogMerchandisingApi.*;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
/** 类目和详情是目录权威，模板只保存不可变版本，查询同时限定租户门店。 */
@Mapper
public interface MerchandisingMapper {
 record ProfileRow(String productId,String storeId,String categoryId,String templateId,Long templateVersion,String description,String imagesJson,long version) { }
 record ItemRow(String skuId,String storeId,String title,String unitPrice,long revision,String status,String productId,String categoryId,String categoryName,String barcode,String specificationsJson,String description,String imagesJson) { }
 Category categoryLock(String tenant,String store,String id);
 void categoryInsert(String tenant,CategoryInput input,int depth);
 int categoryChange(String tenant,String id,CategoryChange input);
 boolean categoryUsed(String tenant,String store,String id);
 List<Category> categories(String tenant,String store,String after,int limit,boolean publicOnly);
 void templateInsert(String tenant,Template input,String json);
 String template(String tenant,String store,String id,long version);
 List<String> templates(String tenant,String store,String after,int limit);
 ProfileRow profile(String tenant,String store,String product);
 ProfileRow profileCurrent(String tenant,String store,String product);
 void profileInsert(String tenant,String product,ProfileChange input,String images);
 int profileChange(String tenant,String product,ProfileChange input,String images);
 boolean hasVariants(String tenant,String store,String product);
 Barcode barcode(String tenant,String store,String sku);
 Barcode barcodeCurrent(String tenant,String store,String sku);
 void barcodeInsert(String tenant,String sku,BarcodeChange input);
 int barcodeChange(String tenant,String sku,BarcodeChange input);
 List<ItemRow> search(String tenant,Search input,String channel,java.time.Instant now);
 ItemRow item(String tenant,String store,String sku,String channel,java.time.Instant now);
}

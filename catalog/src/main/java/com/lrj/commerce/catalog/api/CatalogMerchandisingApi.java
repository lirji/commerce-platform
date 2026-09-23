package com.lrj.commerce.catalog.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
/** 商品经营资料独立于价格快照，所有管理读写复用门店资源权限。 */
public interface CatalogMerchandisingApi {
 record CategoryInput(String categoryId,String storeId,String parentId,String name) { }
 record Category(String categoryId,String storeId,String parentId,String name,int depth,String status,long version) { }
 record CategoryChange(String storeId,long expectedVersion,String name,String status,String reason) { }
 record Attribute(String name,List<String> values) { }
 record Template(String templateId,long version,String storeId,String name,List<Attribute> fields) { }
 record Picture(String url,String alt) { }
 record Profile(String productId,String storeId,String categoryId,String templateId,Long templateVersion,String description,List<Picture> images,long version) { }
 record ProfileChange(String storeId,long expectedVersion,String categoryId,String templateId,Long templateVersion,String description,List<Picture> images,String reason) { }
 record Barcode(String skuId,String storeId,String barcode,long version) { }
 record BarcodeChange(String storeId,long expectedVersion,String barcode,String reason) { }
 record Search(String storeId,String q,String categoryId,String minimumPrice,String maximumPrice,String status,String after,int limit) { }
 record Item(String skuId,String storeId,String title,String unitPrice,long revision,String status,String productId,String categoryId,String categoryName,String barcode,List<ProductOperationsApi.Specification> specifications,String description,List<Picture> images) { }
 Category createCategory(Actor actor,String key,CategoryInput input);
 Category changeCategory(Actor actor,String key,String id,CategoryChange input);
 List<Category> categories(Actor actor,String store,String after,int limit,boolean publicOnly);
 Template createTemplate(Actor actor,String key,Template input);
 Template template(Actor actor,String store,String id,long version);
 List<Template> templates(Actor actor,String store,String after,int limit);
 Profile profile(Actor actor,String store,String product);
 Profile changeProfile(Actor actor,String key,String product,ProfileChange input);
 Barcode barcode(Actor actor,String store,String sku);
 Barcode changeBarcode(Actor actor,String key,String sku,BarcodeChange input);
 List<Item> search(Actor actor,Search input,boolean operations);
 Item item(Actor actor,String store,String sku);
}

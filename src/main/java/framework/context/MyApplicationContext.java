package framework.context;

import framework.annotation.MyAutowired;
import framework.annotation.MyController;
import framework.annotation.MyService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class MyApplicationContext {
    private List<String> classNameCache = new ArrayList<String>();
    private Map<String,Object> instanceMapping = new ConcurrentHashMap<String, Object>();
    private Properties config = new Properties();
    public MyApplicationContext(String location){
        InputStream inputStream = null;
        try{
            //1、定位
            inputStream =  this.getClass().getClassLoader().getResourceAsStream(location);
            //2、载入
            config.load(inputStream);
            //3、注册，找到包下面的所有类,将类名存到变量容器中
            String packageName = config.getProperty("scanPackage");
            doRegister(packageName);
            //4、实例化
            doCreatBean();
            //5、注入
            populate();
        }catch (Exception e){

        }finally {
            if(inputStream!=null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    public Map<String,Object> getAll(){
        return instanceMapping;
    }
    public Properties getConfig() {
        return config;
    }
    private String lowerFirstLetter(String classSimpleName){
        char [] chars = classSimpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);
    }
    private void doRegister(String packageName) {
        //转换成文件夹包结构
        URL url = this.getClass().getClassLoader().getResource("/" + packageName.replaceAll("\\.","/"));
        File dir = new File(url.getFile());
        for(File file:dir.listFiles()){
            if(file.isDirectory()){
                doRegister(packageName+"."+file.getName());
            }else{
                classNameCache.add(packageName+"."+file.getName().replaceAll(".class","").trim());
            }
        }
    }
    private void doCreatBean() {
        if(classNameCache.isEmpty())return;
        //取出所有的类名进行实例化-反射示例化，放入到Map容器中
        for(String className:classNameCache){
            try {
                Class<?> clazz = Class.forName(className);
                //加了@MyController、@MyService自定义注解的进行初始化
                if(clazz.isAnnotationPresent(MyController.class)){
                    String id = lowerFirstLetter(clazz.getSimpleName());
                    instanceMapping.put(id,clazz.newInstance());
                }else if(clazz.isAnnotationPresent(MyService.class)){
                    MyService myService = clazz.getAnnotation(MyService.class);
                    String id =  myService.value();
                    //如果service注解自定义了名字，那么使用该名字
                    if(!"".equals(id)){
                        instanceMapping.put(id,clazz.newInstance());
                        continue;
                    }
                    //使用接口的类型值名称作为id
                    Class<?>[] interfaces =  clazz.getInterfaces();
                    for (Class<?> i : interfaces) {
                        instanceMapping.put(i.getName(), clazz.newInstance());
                    }
                }else{
                    continue;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    private void populate() {
        if(instanceMapping.isEmpty())return;
        for (Map.Entry<String, Object> entry : instanceMapping.entrySet()) {
            //把所有的属性全部取出来，包括私有属性
            Field[]  fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if(!field.isAnnotationPresent(MyAutowired.class)){ continue; }
                MyAutowired autowired = field.getAnnotation(MyAutowired.class);
                String id = autowired.value().trim();
                //如果id为空，也就是说，自己没有设置，默认根据类型来注入
                if("".equals(id)){
                    id = field.getType().getName();
                }
                field.setAccessible(true); //把私有变量开放访问权限
                try {
                    field.set(entry.getValue(), instanceMapping.get(id));
                } catch (Exception e) {
                    e.printStackTrace();
                    continue;
                }
            }
        }
    }
}

package framework.servlet;

import framework.annotation.MyController;
import framework.annotation.MyRequestMapping;
import framework.annotation.MyRequestParam;
import framework.context.MyApplicationContext;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MyDispatcherServlet extends HttpServlet {
    private static final String LOCATION = "contextConfigLocation";
    private List<Handler> handlerMapping = new ArrayList<Handler>();
    private Map<Handler,HandlerAdapter> adapterMapping = new HashMap<Handler, HandlerAdapter>();
    private List<ViewResolver> viewResolvers = new ArrayList<ViewResolver>();
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        try {
            doDispatch(req,resp);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        try{
            //先取出来一个Handler，从HandlerMapping取
            Handler handler = getHandler(req);
            if(handler == null){
                resp.getWriter().write("404 Not Found");
                return ;
            }

            //再取出来一个适配器
            //再由适配去调用我们具体的方法
            HandlerAdapter ha = getHandlerAdapter(handler);
            MyModelAndView mv = ha.handle(req, resp, handler);
            //自定义模板框架
            applyDefaultViewName(resp, mv);
        }catch(Exception e){
            throw e;
        }
    }
    private void applyDefaultViewName(HttpServletResponse resp, MyModelAndView mv) throws Exception {
        if(null == mv){ return;}
        if(viewResolvers.isEmpty()){ return;}
        for (ViewResolver resolver : viewResolvers) {
            if(!mv.getView().equals(resolver.getViewName())){ continue; }
            String r = resolver.parse(mv);
            if(r != null){
                resp.getWriter().write(r);
                break;
            }
        }
    }
    private HandlerAdapter getHandlerAdapter(Handler handler) {
        if(adapterMapping.isEmpty()){return null;}
        return adapterMapping.get(handler);
    }
    private Handler getHandler(HttpServletRequest req) {
        //循环handlerMapping
        if(handlerMapping.isEmpty()){ return null; }
        String url = req.getRequestURI();
        String contextPath = req.getContextPath();
        url = url.replace(contextPath, "").replaceAll("/+", "/");
        for (Handler handler : handlerMapping) {
            Matcher matcher = handler.pattern.matcher(url);
            if(!matcher.matches()){ continue;}
            return handler;
        }
        return null;
    }
    @Override
    public void init(ServletConfig config) throws ServletException {
        //IOC容器初始化-简单版本new一个
        MyApplicationContext context = new MyApplicationContext(config.getInitParameter(LOCATION));
        //请求解析
        initMultipartResolver(context);
        //多语言、国际化
        initLocaleResolver(context);
        //主题View层的
        initThemeResolver(context);
        //============== 重要 ================
        //解析url和Method的关联关系
        initHandlerMappings(context);
        //适配器（匹配的过程）
        initHandlerAdapters(context);
        //============== 重要 ================
        //异常解析
        initHandlerExceptionResolvers(context);
        //视图转发（根据视图名字匹配到一个具体模板）
        initRequestToViewNameTranslator(context);
        //解析模板中的内容（拿到服务器传过来的数据，生成HTML代码）
        initViewResolvers(context);
        initFlashMapManager(context);
    }
    private void initHandlerMappings(MyApplicationContext context) {
        //获取所有实例化的类，找到@MyController类，封装List->value:Handler对象
        Map<String,Object>  ioc =  context.getAll();
        if(ioc.isEmpty())return;
        //只要是由Cotroller修饰类，里面方法全部找出来
        //而且这个方法上应该要加了RequestMaping注解，如果没加这个注解，这个方法是不能被外界来访问的
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz =  entry.getValue().getClass();
            if(!clazz.isAnnotationPresent(MyController.class)){continue;}
            String url = "";
            if(clazz.isAnnotationPresent(MyRequestMapping.class)){
                MyRequestMapping requestMapping = clazz.getAnnotation(MyRequestMapping.class);
                url = requestMapping.value();
            }
            //扫描Controller下面的所有的方法
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                if(!method.isAnnotationPresent(MyRequestMapping.class)){ continue;}
                MyRequestMapping requestMapping = method.getAnnotation(MyRequestMapping.class);
                String regex = (url + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                handlerMapping.add(new Handler(pattern,entry.getValue(),method));
                System.out.println("Mapping: " + regex + " " +  method.toString());
            }
        }
    }
    private void initHandlerAdapters(MyApplicationContext context) {
        if(handlerMapping.isEmpty())return;
        //循环封装好的List<Handler>集合，给方法的参数进行匹配封装,然后封装到map->key:handle,value:handlerAdapter
        Map<String,Integer> paramMapping = new HashMap<String,Integer>();
        for(Handler handler:handlerMapping){
            //把这个方法上面所有的参数全部获取到
            Class<?> [] paramsTypes = handler.method.getParameterTypes();
            //循环参数，按顺序放入到map中
            for (int i = 0;i < paramsTypes.length ; i ++) {
                Class<?> type = paramsTypes[i];
                //Request和Response参数
                if(type == HttpServletRequest.class ||
                        type == HttpServletResponse.class){
                    paramMapping.put(type.getName(), i);
                }
            }
            //获取自定义参数
            Annotation [][] pa =  handler.method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i ++) {
                for(Annotation a : pa[i]){
                    if(a instanceof MyRequestParam){
                        String paramName = ((MyRequestParam) a).value();
                        if(!"".equals(paramName.trim())){
                            paramMapping.put(paramName, i);
                        }

                    }
                }
            }
            adapterMapping.put(handler,new HandlerAdapter(paramMapping));
        }
    }
    private void initViewResolvers(MyApplicationContext context) {
        String tempateRoot = context.getConfig().getProperty("templateRoot");
        //归根到底就是一个文件，普通文件
        String rootPath = this.getClass().getClassLoader().getResource(tempateRoot).getFile();
        File rootDir = new File(rootPath);
        for (File template : rootDir.listFiles()) {
            viewResolvers.add(new ViewResolver(template.getName(),template));
        }
    }
    class Handler{
        protected Pattern pattern;
        protected Object controller;
        protected Method method;
        public Handler(Pattern pattern, Object controller, Method method) {
            this.pattern = pattern;
            this.controller = controller;
            this.method = method;
        }
    }
    class HandlerAdapter {
        private Map<String,Integer> paramMapping;

        public HandlerAdapter(Map<String,Integer> paramMapping){
            this.paramMapping = paramMapping;
        }
        //主要目的是用反射调用url对应的method
        public MyModelAndView handle(HttpServletRequest req, HttpServletResponse resp,Handler handler) throws Exception {
            //给req赋值、给resp赋值、handler-处理handler对象
            Class<?>[] paramTypes = handler.method.getParameterTypes();
            //要想给参数赋值，只能通过索引号来找到具体的某个参数
            Object[] paramValues = new Object[paramTypes.length];
            Map<String, String[]> params = req.getParameterMap();
            for (Map.Entry<String, String[]> param : params.entrySet()) {
                String value = Arrays.toString(param.getValue()).replaceAll("\\[|\\]", "").replaceAll(",\\s", ",");
                if (!this.paramMapping.containsKey(param.getKey())) {
                    continue;
                }
                int index = this.paramMapping.get(param.getKey());
                //单个赋值是不行的
                paramValues[index] = castStringValue(value, paramTypes[index]);
            }
            //request 和 response 要赋值
            String reqName = HttpServletRequest.class.getName();
            if (this.paramMapping.containsKey(reqName)) {
                int reqIndex = this.paramMapping.get(reqName);
                paramValues[reqIndex] = req;
            }
            String resqName = HttpServletResponse.class.getName();
            if (this.paramMapping.containsKey(resqName)) {
                int respIndex = this.paramMapping.get(resqName);
                paramValues[respIndex] = resp;
            }
            boolean isModelAndView = handler.method.getReturnType() == MyModelAndView.class;
            Object r = handler.method.invoke(handler.controller, paramValues);
            if (isModelAndView) {
                return (MyModelAndView) r;
            } else {
                return null;
            }
        }

        private Object castStringValue(String value,Class<?> clazz){
            if(clazz == String.class){
                return value;
            }else if(clazz == Integer.class){
                return Integer.valueOf(value);
            }else if(clazz == int.class){
                return Integer.valueOf(value).intValue();
            }else{
                return null;
            }
        }
    }
    private class ViewResolver{
        private String viewName;
        private File file;

        protected ViewResolver(String viewName,File file){
            this.viewName = viewName;
            this.file = file;
        }
        protected String parse(MyModelAndView mv) throws Exception{
            StringBuffer sb = new StringBuffer();
            RandomAccessFile ra = new RandomAccessFile(this.file, "r");
            try{
                //模板框架的语法是非常复杂，但是，原理是一样的
                //无非都是用正则表达式来处理字符串而已
                //就这么简单，不要认为这个模板框架的语法是有多么的高大上
                //来我现在来做一个最接地气的模板，也就是咕泡学院独创的模板语法
                String line = null;
                while(null != (line = ra.readLine())){
                    Matcher m = matcher(line);
                    while (m.find()) {
                        for (int i = 1; i <= m.groupCount(); i ++) {
                            String paramName = m.group(i);
                            Object paramValue = mv.getModel().get(paramName);
                            if(null == paramValue){ continue; }
                            line = line.replaceAll("@\\{" + paramName + "\\}", paramValue.toString());
                        }
                    }

                    sb.append(line);
                }
            }finally{
                ra.close();
            }
            return sb.toString();
        }
        private Matcher matcher(String str){
            Pattern pattern = Pattern.compile("@\\{(.+?)\\}",Pattern.CASE_INSENSITIVE);
            Matcher m = pattern.matcher(str);
            return m;
        }
        public String getViewName() {
            return viewName;
        }
    }
    private void initRequestToViewNameTranslator(MyApplicationContext context) {
        //springmvc实现了，但是不是重要步骤，简化不处理
    }
    private void initHandlerExceptionResolvers(MyApplicationContext context) {
        //springmvc实现了，但是不是重要步骤，简化不处理
    }
    private void initThemeResolver(MyApplicationContext context) {
        //springmvc实现了，但是不是重要步骤，简化不处理
    }
    private void initLocaleResolver(MyApplicationContext context) {
        //springmvc实现了，但是不是重要步骤，简化不处理
    }
    private void initMultipartResolver(MyApplicationContext context) {
        //springmvc实现了，但是不是重要步骤，简化不处理
    }
    private void initFlashMapManager(MyApplicationContext context) {
        //springmvc实现了，但是不是重要步骤，简化不处理
    }
}

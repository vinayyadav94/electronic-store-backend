package com.lcwd.electronic.store.controllers;

import com.lcwd.electronic.store.dtos.OrderDto;
import com.lcwd.electronic.store.dtos.UserDto;
import com.lcwd.electronic.store.services.OrderService;
import com.lcwd.electronic.store.services.UserService;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    @Autowired
    private UserService userService;
    @Autowired
    private OrderService orderService;

    @Value("${razorpayKey}")
    private String key;
    @Value("${razorpaySecret}")
    private String secret;

    @PostMapping("/initiate-payment/{orderId}")
    public ResponseEntity<?> initiatePayment(@PathVariable String orderId, Principal principal){

        UserDto userDto = this.userService.getUserByEmail(principal.getName());
        OrderDto ourOrder = this.orderService.getOrder(orderId);

        //RazorPay API to create order
        try{
            RazorpayClient razorpayClient = new RazorpayClient(key, secret);

            JSONObject orderRequest = new JSONObject();
            orderRequest.put("amount", ourOrder.getOrderAmount()*100);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "receipt_orderId");

            //create order
            Order order = razorpayClient.orders.create(orderRequest);
            //save the order id to backend
            ourOrder.setRazorpayOrderId(order.get("id"));
            //ourOrder.setPaymentStatus(order.get("status").toString().toUpperCase());
            this.orderService.updateOrder(ourOrder.getOrderId(), ourOrder);
            System.out.println(order);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "orderId", ourOrder.getOrderId(),
                    "razorpayOrderId", ourOrder.getRazorpayOrderId(),
                    "amount", ourOrder.getOrderAmount(),
                    "status", ourOrder.getPaymentStatus()
            ));
        } catch (RazorpayException ex) {
            ex.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message","Error in creating order!"));
        }

    }

    @PostMapping("/captureAndVerifyPayment/{orderId}")
    public ResponseEntity<?> verifyAndSavePayment(
        @RequestBody Map<String, Object> data,
        @PathVariable String orderId
    ){
        String razorpayOrderId = data.get("razorpayOrderId").toString();
        String razorpayPaymentId = data.get("razorpayPaymentId").toString();
        String razorpayPaymentSignature = data.get("razorpayPaymentSignature").toString();

        //store data as per your need
        OrderDto order = this.orderService.getOrder(orderId);
        order.setPaymentStatus("PAID");
        order.setPaymentId(razorpayPaymentId);
        order.setOrderStatus("CONFIRMED");
        this.orderService.updateOrder(orderId, order);

        try{
            RazorpayClient razorpayClient = new RazorpayClient(key, secret);
            JSONObject options = new JSONObject();
            options.put("razorpay_order_id", razorpayOrderId);
            options.put("razorpay_payment_id", razorpayPaymentId);
            options.put("razorpay_signature", razorpayPaymentSignature);

            boolean b = Utils.verifyPaymentSignature(options, secret);
            if(b){
                System.out.println("payment signature verified");
                return new ResponseEntity<>(
                        Map.of(
                                "message","Payment Done",
                                "success", true,
                                "signatureVerified", true
                        )
                ,HttpStatus.OK);
            }else{
                System.out.println("payment signature not verified");
                return new ResponseEntity<>(
                        Map.of(
                                "message","Payment done",
                                "success", false,
                                "signatureVerified", false
                        )
                        ,HttpStatus.OK);
            }
        } catch(RazorpayException e){
            throw new RuntimeException(e);
        }
    }
}
